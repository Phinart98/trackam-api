package com.trackam.service;

import com.trackam.ai.AdvisorPrompt;
import com.trackam.ai.ImageParserPrompt;
import com.trackam.ai.InsightPrompt;
import com.trackam.ai.TextParserPrompt;
import com.trackam.ai.guardrails.InputGuardrail;
import com.trackam.ai.guardrails.OutputGuardrail;
import com.trackam.ai.tools.AdvisorTools;
import com.trackam.config.AppProperties;
import com.trackam.dto.AdvisorRequest;
import com.trackam.dto.InsightRequest;
import com.trackam.dto.AdvisorResponse;
import com.trackam.dto.ParsedTransactionResponse;
import com.trackam.exception.TrackAmException;
import com.trackam.model.ChatMessage;
import com.trackam.model.ChatSession;
import com.trackam.model.CustomCategory;
import com.trackam.model.Transaction;
import com.trackam.repository.ChatMessageRepository;
import com.trackam.repository.ChatSessionRepository;
import com.trackam.repository.CustomCategoryRepository;
import com.trackam.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiService {

    private final ChatClient groqChatClient;
    private final ChatClient geminiLiteChatClient;
    private final ChatClient geminiFlashChatClient;
    private final ChatClient cerebrasChatClient; // nullable — only present if API key configured
    private final OpenAiAudioTranscriptionModel transcriptionModel;
    private final AdvisorTools advisorTools;
    private final AuditService auditService;
    private final EmbeddingService embeddingService;
    private final ExchangeRateService exchangeRateService;
    private final TransactionRepository txRepo;
    private final CustomCategoryRepository categoryRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final ChatSessionRepository chatSessionRepo;
    private final AppProperties props;

    public AiService(
        @Qualifier("groqChatClient") ChatClient groqChatClient,
        @Qualifier("geminiLiteChatClient") ChatClient geminiLiteChatClient,
        @Qualifier("geminiFlashChatClient") ChatClient geminiFlashChatClient,
        @Qualifier("cerebrasChatClient") Optional<ChatClient> cerebrasChatClient,
        OpenAiAudioTranscriptionModel transcriptionModel,
        AdvisorTools advisorTools,
        AuditService auditService,
        EmbeddingService embeddingService,
        ExchangeRateService exchangeRateService,
        TransactionRepository txRepo,
        CustomCategoryRepository categoryRepo,
        ChatMessageRepository chatMessageRepo,
        ChatSessionRepository chatSessionRepo,
        AppProperties props
    ) {
        this.groqChatClient = groqChatClient;
        this.geminiLiteChatClient = geminiLiteChatClient;
        this.geminiFlashChatClient = geminiFlashChatClient;
        this.cerebrasChatClient = cerebrasChatClient.orElse(null);
        this.transcriptionModel = transcriptionModel;
        this.advisorTools = advisorTools;
        this.auditService = auditService;
        this.embeddingService = embeddingService;
        this.exchangeRateService = exchangeRateService;
        this.txRepo = txRepo;
        this.categoryRepo = categoryRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.chatSessionRepo = chatSessionRepo;
        this.props = props;
    }

    /** Load user's custom categories for dynamic prompt building */
    private List<CustomCategory> getUserCategories(UUID userId) {
        try {
            return categoryRepo.findByUserIdOrderBySortOrderAsc(userId);
        } catch (Exception e) {
            log.warn("Failed to load custom categories for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** Text parsing: primary = Gemini Flash-Lite (cheap), fallbacks = Groq → Gemini Flash → Cerebras */
    public ParsedTransactionResponse parseText(String text, String currency, UUID userId) {
        checkDailyLimit(userId);
        InputGuardrail.validateText(text);

        List<CustomCategory> userCategories = getUserCategories(userId);
        List<String> customCategoryIds = userCategories.stream().map(CustomCategory::getId).toList();
        String systemPrompt = TextParserPrompt.build(userCategories);
        String today = java.time.LocalDate.now(ZoneOffset.UTC).toString();
        String userPrompt = "Today's date: " + today + "\nCurrency context: " + currency + "\nParse this transaction: " + text;
        ParsedTransactionResponse result = callWithFallback(
            userId, "parse-text",
            List.of("gemini-lite", "groq", "gemini-flash", "cerebras"),
            systemPrompt, userPrompt,
            ParsedTransactionResponse.class
        );
        return applyFxConversion(OutputGuardrail.validate(result, customCategoryIds), currency);
    }

    /** Image parsing: primary = Groq/Llama 4 Scout (best vision), fallback = Gemini Flash */
    public ParsedTransactionResponse parseImage(MultipartFile file, String currency, UUID userId) throws IOException {
        checkDailyLimit(userId);
        InputGuardrail.validateImage(file);

        List<CustomCategory> userCategories = getUserCategories(userId);
        List<String> customCategoryIds = userCategories.stream().map(CustomCategory::getId).toList();
        String imageSystemPrompt = ImageParserPrompt.build(userCategories, currency);

        // Read bytes ONCE — MultipartFile input stream is consumed after first read
        byte[] imageBytes = file.getBytes();
        Resource imageResource = new ByteArrayResource(imageBytes);
        String rawType = file.getContentType();
        var mimeType = (rawType != null && !rawType.isBlank())
            ? MimeTypeUtils.parseMimeType(rawType)
            : MimeTypeUtils.IMAGE_JPEG;

        // Build message once — imageResource wraps in-memory bytes, safe to reuse
        String todayForImage = java.time.LocalDate.now(ZoneOffset.UTC).toString();
        var media = new Media(mimeType, imageResource);
        var userMessage = UserMessage.builder()
            .text("Today's date: " + todayForImage + "\nExtract all transactions from this image. Return structured JSON.")
            .media(List.of(media))
            .build();

        long start = System.currentTimeMillis();
        String primaryProvider = "groq";
        try {
            ParsedTransactionResponse result = groqChatClient.prompt()
                .system(imageSystemPrompt)
                .messages(List.of(userMessage))
                .call()
                .entity(ParsedTransactionResponse.class);

            auditService.log(userId, "parse-image", primaryProvider,
                System.currentTimeMillis() - start, true, null);
            return applyFxConversion(OutputGuardrail.validate(result, customCategoryIds), currency);

        } catch (Exception e) {
            auditService.log(userId, "parse-image", primaryProvider,
                System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("Groq image parse failed: {}. Falling back to Gemini Flash.", e.getMessage());

            long fallbackStart = System.currentTimeMillis();
            try {
                ParsedTransactionResponse result = geminiFlashChatClient.prompt()
                    .system(imageSystemPrompt)
                    .messages(List.of(userMessage))
                    .call()
                    .entity(ParsedTransactionResponse.class);

                auditService.log(userId, "parse-image", "gemini-flash",
                    System.currentTimeMillis() - fallbackStart, true, null);
                return applyFxConversion(OutputGuardrail.validate(result, customCategoryIds), currency);

            } catch (Exception fallbackEx) {
                auditService.log(userId, "parse-image", "gemini-flash",
                    System.currentTimeMillis() - fallbackStart, false, fallbackEx.getMessage());
                throw new TrackAmException("Image parsing failed. Please try text or manual input.");
            }
        }
    }

    /**
     * If the AI detected a different currency on the receipt, convert to the user's preferred currency.
     * Sets originalCurrency/originalAmount/exchangeRate fields; amount/currency reflect the converted values.
     * Returns the validated response unchanged if currencies match or conversion fails.
     */
    private ParsedTransactionResponse applyFxConversion(ParsedTransactionResponse validated, String userCurrency) {
        String receiptCurrency = validated.currency();
        if (receiptCurrency == null) {
            log.warn("AI returned null currency — skipping FX conversion, storing at face value");
            return validated;
        }
        if (!receiptCurrency.equalsIgnoreCase(userCurrency)) {
            ExchangeRateService.ExchangeResult fx = exchangeRateService.convert(
                validated.amount(), receiptCurrency, userCurrency, validated.date());
            if (fx != null) {
                return new ParsedTransactionResponse(
                    fx.convertedAmount(), userCurrency,
                    validated.category(), validated.type(),
                    validated.description(), validated.vendor(),
                    validated.date(), validated.confidence(),
                    receiptCurrency, validated.amount(), fx.rate()
                );
            }
        }
        return validated; // OutputGuardrail already sets FX fields to null
    }

    /**
     * Financial advisor: uses tool-calling with fallback to context stuffing.
     *
     * Primary: Gemini Flash calls @Tool methods (SQL queries on user's transactions).
     * Fallback: If tool-calling fails, stuff all transactions into the prompt as context.
     */
    @Transactional
    public AdvisorResponse askAdvisor(String question, AdvisorRequest.AdvisorContext ctx,
                                      UUID sessionId, UUID userId) {
        checkDailyLimit(userId);
        InputGuardrail.validateAdvisorQuestion(question);

        // Resolve or create chat session
        ChatSession session = resolveSession(sessionId, userId, question);

        // Load conversation history (last 10 messages, most recent first from DB, reversed for chronological order)
        List<ChatMessage> history = chatMessageRepo.findTop10BySessionIdOrderByCreatedAtDesc(session.getId())
            .reversed();

        // Proper role-based history — the model treats this as real dialogue, not injected text
        List<Message> historyMessages = history.stream()
            .map(msg -> ChatMessage.ROLE_USER.equals(msg.getRole())
                ? (Message) new UserMessage(msg.getContent())
                : new AssistantMessage(msg.getContent()))
            .toList();

        // System prompt: financial context only — history is passed as proper message objects
        String contextBlock = buildFinancialContext(ctx);
        String systemPrompt = AdvisorPrompt.SYSTEM + "\n\n" + contextBlock;

        // Attempt 1: Tool-calling via Gemini Flash (the model decides which SQL queries to run)
        String reply = null;
        long start = System.currentTimeMillis();
        try {
            reply = geminiFlashChatClient.prompt()
                .system(systemPrompt)
                .messages(historyMessages)
                .user(question)
                .tools(advisorTools)
                .toolContext(Map.of("userId", userId))
                .call()
                .content();

            auditService.log(userId, "advisor", "gemini-flash-tools",
                System.currentTimeMillis() - start, true, null);

        } catch (Exception e) {
            auditService.log(userId, "advisor", "gemini-flash-tools",
                System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("Tool-calling advisor failed: {}. Falling back to context stuffing.", e.getMessage());
        }

        // Attempt 2: Context stuffing — serialize all transactions into the prompt
        if (reply == null || reply.isBlank()) {
            reply = advisorWithContextStuffing(userId, question, systemPrompt, historyMessages);
        }

        // Both messages persisted atomically — if the AI call above threw, neither is saved.
        chatMessageRepo.saveAll(List.of(
            ChatMessage.builder().userId(userId).sessionId(session.getId()).role(ChatMessage.ROLE_USER).content(question).build(),
            ChatMessage.builder().userId(userId).sessionId(session.getId()).role(ChatMessage.ROLE_ASSISTANT).content(reply).build()
        ));

        return new AdvisorResponse(reply, session.getId().toString());
    }

    /**
     * Fallback advisor: stuffs all user transactions into the prompt as context.
     * Also passes conversation history as proper message objects for natural dialogue.
     */
    private String advisorWithContextStuffing(UUID userId, String question, String baseSystemPrompt,
                                              List<Message> historyMessages) {
        List<Transaction> allTxs = txRepo.findRecentTransactions(userId, PageRequest.of(0, 100));
        String txSummary = allTxs.isEmpty()
            ? "No transactions recorded yet."
            : formatTransactions(allTxs);

        String enrichedSystem = baseSystemPrompt + "\n\nAll user transactions:\n" + txSummary;
        return callWithFallbackMessages(userId, "advisor-context",
            List.of("gemini-flash", "groq", "gemini-lite", "cerebras"),
            enrichedSystem, historyMessages, question);
    }

    /**
     * Generic fallback chain for message-history-aware calls (returns raw String content).
     * Mirrors callWithFallback but uses .messages() + .content() instead of .user() + .entity().
     */
    private String callWithFallbackMessages(UUID userId, String operation,
                                             List<String> providerOrder,
                                             String system, List<Message> history, String user) {
        Exception lastException = null;
        for (String provider : providerOrder) {
            ChatClient client = selectClient(provider);
            if (client == null) continue;
            long start = System.currentTimeMillis();
            try {
                String result = client.prompt()
                    .system(system)
                    .messages(history)
                    .user(user)
                    .call()
                    .content();
                auditService.log(userId, operation, provider, System.currentTimeMillis() - start, true, null);
                return result;
            } catch (Exception e) {
                auditService.log(userId, operation, provider, System.currentTimeMillis() - start, false, e.getMessage());
                log.warn("{} failed for {}: {}. Trying next provider.", provider, operation, e.getMessage());
                lastException = e;
            }
        }
        throw new TrackAmException("All AI providers failed. Please try again later.", lastException);
    }

    /**
     * Dashboard AI Insight: lightweight single-paragraph insight from a compact snapshot.
     * Uses Gemini Flash-Lite (cheapest) — no tool-calling, no history, fast response.
     * Refreshed client-side only when transactions change or 6h have elapsed.
     */
    public String generateInsight(InsightRequest req, UUID userId) {
        checkDailyLimit(userId);
        String cur = req.currency() != null ? req.currency() : "GHS";
        String context = InsightPrompt.buildContext(
            cur,
            cur + " " + (req.totalIncome() != null ? req.totalIncome() : "0"),
            cur + " " + (req.totalExpenses() != null ? req.totalExpenses() : "0"),
            cur + " " + (req.balance() != null ? req.balance() : "0"),
            req.burnPercent(), req.daysRemaining(),
            req.topCategoryName() != null ? req.topCategoryName() : "General",
            req.topCategoryPercent(),
            req.trend() != null ? req.trend() : "stable",
            req.transactionCount(),
            req.recentAnomaly()
        );

        return callWithFallback(
            userId, "insight",
            List.of("gemini-lite", "groq", "gemini-flash"),
            InsightPrompt.SYSTEM + "\n\n" + context,
            "Write the insight now.",
            String.class
        );
    }

    /**
     * Build financial context from the frontend-provided summary.
     * This gives the LLM baseline numbers even before tool calls.
     */
    private String buildFinancialContext(AdvisorRequest.AdvisorContext ctx) {
        if (ctx == null) return "";
        String cur = ctx.currency() != null ? ctx.currency() : "GHS";
        String income = ctx.totalIncome() != null ? cur + " " + ctx.totalIncome() : "unknown";
        String expenses = ctx.totalExpenses() != null ? cur + " " + ctx.totalExpenses() : "unknown";
        String balance = ctx.balance() != null ? cur + " " + ctx.balance() : "unknown";
        return AdvisorPrompt.buildContext(
            cur, income, expenses, balance,
            ctx.topCategory(),
            ctx.transactionCount(),
            "(Detailed data available via tools — ask specific questions for analysis)"
        );
    }

    /**
     * Generic fallback chain: tries each provider in order, stops on first success.
     * Every attempt (success or failure) is written to audit_logs.
     */
    private <T> T callWithFallback(UUID userId, String operation,
                                    List<String> providerOrder,
                                    String system, String user, Class<T> type) {
        Exception lastException = null;
        for (String provider : providerOrder) {
            ChatClient client = selectClient(provider);
            if (client == null) continue; // skip unconfigured providers

            long start = System.currentTimeMillis();
            try {
                T result = client.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .entity(type);
                if (result == null) {
                    auditService.log(userId, operation, provider,
                        System.currentTimeMillis() - start, false, "null response");
                    log.warn("{} returned null for {}. Trying next provider.", provider, operation);
                    continue;
                }
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
        throw new TrackAmException("All AI providers failed. Please try again later.",
            lastException);
    }

    private ChatClient selectClient(String provider) {
        return switch (provider) {
            case "groq" -> groqChatClient;
            case "gemini-lite" -> geminiLiteChatClient;
            case "gemini-flash" -> geminiFlashChatClient;
            case "cerebras" -> cerebrasChatClient; // may be null
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    private ChatSession resolveSession(UUID sessionId, UUID userId, String firstMessage) {
        if (sessionId != null) {
            return chatSessionRepo.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseGet(() -> createSession(userId, firstMessage));
        }
        return createSession(userId, firstMessage);
    }

    private ChatSession createSession(UUID userId, String firstMessage) {
        String title = firstMessage != null && firstMessage.length() > 50
            ? firstMessage.substring(0, 50) + "..."
            : firstMessage;
        return chatSessionRepo.save(ChatSession.builder()
            .userId(userId)
            .title(title)
            .build());
    }

    /**
     * Transcribes audio via Groq Whisper (whisper-large-v3-turbo).
     * Auto-configured from spring.ai.openai.* — same key and base-url as chat.
     */
    public String transcribeAudio(MultipartFile audio, UUID userId) throws IOException {
        checkDailyLimit(userId);
        ByteArrayResource audioResource = new ByteArrayResource(audio.getBytes()) {
            @Override
            public String getFilename() {
                String original = audio.getOriginalFilename();
                return (original != null && !original.isBlank()) ? original : "audio.webm";
            }
        };
        long start = System.currentTimeMillis();
        try {
            String transcript = transcriptionModel
                .call(new AudioTranscriptionPrompt(audioResource))
                .getResult()
                .getOutput();
            auditService.log(userId, "transcribe", "groq", System.currentTimeMillis() - start, true, null);
            return transcript;
        } catch (Exception e) {
            auditService.log(userId, "transcribe", "groq", System.currentTimeMillis() - start, false, e.getMessage());
            throw e;
        }
    }

    private void checkDailyLimit(UUID userId) {
        if (auditService.isOverDailyLimit(userId, props.getMaxDailyCalls())) {
            throw new TrackAmException("Daily AI call limit reached. Try again tomorrow.");
        }
    }

    private String formatTransactions(List<Transaction> transactions) {
        return transactions.stream()
            .map(t -> "%s %s %s %s (%s)".formatted(
                t.getDate().atZone(ZoneOffset.UTC).toLocalDate(),
                t.getType(), t.getCategory(),
                t.getAmount().toPlainString(), t.getDescription()))
            .collect(Collectors.joining("\n"));
    }

}
