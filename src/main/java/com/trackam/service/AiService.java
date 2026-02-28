package com.trackam.service;

import com.trackam.ai.AdvisorPrompt;
import com.trackam.ai.ImageParserPrompt;
import com.trackam.ai.TextParserPrompt;
import com.trackam.ai.guardrails.InputGuardrail;
import com.trackam.ai.guardrails.OutputGuardrail;
import com.trackam.config.AppProperties;
import com.trackam.dto.AdvisorRequest;
import com.trackam.dto.AdvisorResponse;
import com.trackam.dto.ParsedTransactionResponse;
import com.trackam.model.ChatMessage;
import com.trackam.model.ChatSession;
import com.trackam.model.Transaction;
import com.trackam.repository.ChatMessageRepository;
import com.trackam.repository.ChatSessionRepository;
import com.trackam.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Media;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiService {

    private final ChatClient groqChatClient;
    private final ChatClient geminiLiteChatClient;
    private final ChatClient geminiFlashChatClient;
    private final AuditService auditService;
    private final EmbeddingService embeddingService;
    private final TransactionRepository txRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final ChatSessionRepository chatSessionRepo;
    private final AppProperties props;

    public AiService(
        @Qualifier("groqChatClient") ChatClient groqChatClient,
        @Qualifier("geminiLiteChatClient") ChatClient geminiLiteChatClient,
        @Qualifier("geminiFlashChatClient") ChatClient geminiFlashChatClient,
        AuditService auditService,
        EmbeddingService embeddingService,
        TransactionRepository txRepo,
        ChatMessageRepository chatMessageRepo,
        ChatSessionRepository chatSessionRepo,
        AppProperties props
    ) {
        this.groqChatClient = groqChatClient;
        this.geminiLiteChatClient = geminiLiteChatClient;
        this.geminiFlashChatClient = geminiFlashChatClient;
        this.auditService = auditService;
        this.embeddingService = embeddingService;
        this.txRepo = txRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.chatSessionRepo = chatSessionRepo;
        this.props = props;
    }

    /** Text parsing: primary = Gemini Flash-Lite (cheap), fallbacks = Groq → Gemini Flash */
    public ParsedTransactionResponse parseText(String text, String currency, String userId) {
        checkDailyLimit(userId);
        InputGuardrail.validateText(text);

        String userPrompt = "Currency context: " + currency + "\nParse this transaction: " + text;
        ParsedTransactionResponse result = callWithFallback(
            userId, "parse-text",
            List.of("gemini-lite", "groq", "gemini-flash"),
            TextParserPrompt.SYSTEM, userPrompt,
            ParsedTransactionResponse.class
        );
        return OutputGuardrail.validate(result);
    }

    /** Image parsing: primary = Groq/Llama 4 Scout (best vision), fallback = Gemini Flash */
    public ParsedTransactionResponse parseImage(MultipartFile file, String userId) throws IOException {
        checkDailyLimit(userId);

        long start = System.currentTimeMillis();
        String primaryProvider = "groq";
        try {
            var media = new Media(
                MimeTypeUtils.parseMimeType(file.getContentType()),
                file.getResource()
            );
            var userMessage = UserMessage.builder()
                .text("Extract all transactions from this image. Return structured JSON.")
                .media(List.of(media))
                .build();

            ParsedTransactionResponse result = groqChatClient.prompt()
                .system(ImageParserPrompt.SYSTEM)
                .messages(List.of(userMessage))
                .call()
                .entity(ParsedTransactionResponse.class);

            auditService.log(userId, "parse-image", primaryProvider,
                System.currentTimeMillis() - start, true, null);
            return OutputGuardrail.validate(result);

        } catch (Exception e) {
            auditService.log(userId, "parse-image", primaryProvider,
                System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("Groq image parse failed: {}. Falling back to Gemini Flash.", e.getMessage());

            long fallbackStart = System.currentTimeMillis();
            try {
                var media = new Media(
                    MimeTypeUtils.parseMimeType(file.getContentType()),
                    file.getResource()
                );
                var userMessage = UserMessage.builder()
                    .text("Extract all transactions from this image. Return structured JSON.")
                    .media(List.of(media))
                    .build();

                ParsedTransactionResponse result = geminiFlashChatClient.prompt()
                    .system(ImageParserPrompt.SYSTEM)
                    .messages(List.of(userMessage))
                    .call()
                    .entity(ParsedTransactionResponse.class);

                auditService.log(userId, "parse-image", "gemini-flash",
                    System.currentTimeMillis() - fallbackStart, true, null);
                return OutputGuardrail.validate(result);

            } catch (Exception fallbackEx) {
                auditService.log(userId, "parse-image", "gemini-flash",
                    System.currentTimeMillis() - fallbackStart, false, fallbackEx.getMessage());
                throw new RuntimeException("Image parsing failed. Please try text or manual input.");
            }
        }
    }

    /** Advisor: primary = Gemini Flash (complex reasoning + RAG), fallback = Groq → Gemini Lite */
    public AdvisorResponse askAdvisor(String question, AdvisorRequest.AdvisorContext ctx,
                                      String sessionId, String userId) {
        checkDailyLimit(userId);
        InputGuardrail.validateAdvisorQuestion(question);

        // Resolve or create chat session
        ChatSession session = resolveSession(sessionId, userId, question);

        // Load conversation history (last 10 messages from this session)
        List<ChatMessage> history = chatMessageRepo.findTop10BySessionIdOrderByCreatedAtAsc(session.getId());

        // Persist user message before calling AI (so it's saved even if AI fails)
        chatMessageRepo.save(ChatMessage.builder()
            .userId(userId)
            .sessionId(session.getId())
            .role("user")
            .content(question)
            .build());

        // RAG: embed the question and find semantically similar transactions
        String txSummary;
        try {
            float[] questionEmbedding = embeddingService.embed(question);
            List<Transaction> relevant = txRepo.findSimilar(userId, questionEmbedding, 20);
            txSummary = formatTransactions(relevant);
        } catch (Exception e) {
            log.warn("RAG embedding failed, falling back to recent transactions: {}", e.getMessage());
            txSummary = formatTransactions(
                txRepo.findByUserIdOrderByDateDesc(userId).stream().limit(30).collect(Collectors.toList())
            );
        }

        String contextBlock = AdvisorPrompt.buildContext(
            ctx.currency(),
            ctx.currency() + " " + ctx.totalIncome(),
            ctx.currency() + " " + ctx.totalExpenses(),
            ctx.currency() + " " + ctx.balance(),
            ctx.topCategory(),
            ctx.transactionCount(),
            txSummary
        );

        String historyBlock = buildConversationHistory(history);
        String systemWithContext = AdvisorPrompt.SYSTEM + "\n\n" + contextBlock
            + (historyBlock.isBlank() ? "" : "\n\n" + historyBlock);

        String reply = callWithFallback(
            userId, "advisor",
            List.of("gemini-flash", "groq", "gemini-lite"),
            systemWithContext, question,
            String.class
        );

        // Persist assistant response
        chatMessageRepo.save(ChatMessage.builder()
            .userId(userId)
            .sessionId(session.getId())
            .role("assistant")
            .content(reply)
            .build());

        return new AdvisorResponse(reply, session.getId());
    }

    /**
     * Generic fallback chain: tries each provider in order, stops on first success.
     * Every attempt (success or failure) is written to audit_logs.
     */
    private <T> T callWithFallback(String userId, String operation,
                                    List<String> providerOrder,
                                    String system, String user, Class<T> type) {
        Exception lastException = null;
        for (String provider : providerOrder) {
            long start = System.currentTimeMillis();
            try {
                ChatClient client = selectClient(provider);
                T result = client.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .entity(type);
                auditService.log(userId, operation, provider,
                    System.currentTimeMillis() - start, true, null);
                return result;
            } catch (Exception e) {
                auditService.log(userId, operation, provider,
                    System.currentTimeMillis() - start, false, e.getMessage());
                log.warn("{} failed for {}: {}. Trying next provider.", provider, operation, e.getMessage());
                lastException = e;
            }
        }
        throw new RuntimeException("All AI providers failed. Please try again later.",
            lastException);
    }

    private ChatClient selectClient(String provider) {
        return switch (provider) {
            case "groq" -> groqChatClient;
            case "gemini-lite" -> geminiLiteChatClient;
            case "gemini-flash" -> geminiFlashChatClient;
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    private ChatSession resolveSession(String sessionId, String userId, String firstMessage) {
        if (sessionId != null && !sessionId.isBlank()) {
            return chatSessionRepo.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseGet(() -> createSession(userId, firstMessage));
        }
        return createSession(userId, firstMessage);
    }

    private ChatSession createSession(String userId, String firstMessage) {
        String title = firstMessage != null && firstMessage.length() > 50
            ? firstMessage.substring(0, 50) + "..."
            : firstMessage;
        return chatSessionRepo.save(ChatSession.builder()
            .userId(userId)
            .title(title)
            .build());
    }

    private String buildConversationHistory(List<ChatMessage> messages) {
        if (messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Recent conversation:\n");
        for (ChatMessage msg : messages) {
            sb.append("user".equals(msg.getRole()) ? "User: " : "Assistant: ");
            sb.append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private void checkDailyLimit(String userId) {
        if (auditService.isOverDailyLimit(userId, props.getMaxDailyCalls())) {
            throw new RuntimeException("Daily AI call limit reached. Try again tomorrow.");
        }
    }

    private String formatTransactions(List<Transaction> transactions) {
        return transactions.stream()
            .map(t -> "%s %s %s %.2f (%s)".formatted(
                t.getDate().toLocalDate(), t.getType(), t.getCategory(),
                t.getAmount(), t.getDescription()))
            .collect(Collectors.joining("\n"));
    }
}
