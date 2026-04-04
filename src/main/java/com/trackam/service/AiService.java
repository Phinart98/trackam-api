package com.trackam.service;

import com.trackam.ai.AdvisorPrompt;
import com.trackam.ai.ImageParserPrompt;
import com.trackam.ai.InsightPrompt;
import com.trackam.ai.TextParserPrompt;
import com.trackam.ai.guardrails.InputGuardrail;
import com.trackam.ai.guardrails.OutputGuardrail;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class AiService {

    private final ChatClient groqChatClient;
    private final ChatClient geminiLiteChatClient;
    private final ChatClient geminiFlashChatClient;
    private final ChatClient cerebrasChatClient; // nullable — only present if API key configured
    private final AuditService auditService;
    private final ExchangeRateService exchangeRateService;
    private final TransactionRepository txRepo;
    private final CustomCategoryRepository categoryRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final ChatSessionRepository chatSessionRepo;
    private final RestTemplate restTemplate;
    private final AppProperties props;

    @Value("${spring.ai.openai.api-key:}")
    private String groqApiKey;

    public AiService(
        @Qualifier("groqChatClient") ChatClient groqChatClient,
        @Qualifier("geminiLiteChatClient") ChatClient geminiLiteChatClient,
        @Qualifier("geminiFlashChatClient") ChatClient geminiFlashChatClient,
        @Qualifier("cerebrasChatClient") Optional<ChatClient> cerebrasChatClient,
        AuditService auditService,
        ExchangeRateService exchangeRateService,
        TransactionRepository txRepo,
        CustomCategoryRepository categoryRepo,
        ChatMessageRepository chatMessageRepo,
        ChatSessionRepository chatSessionRepo,
        RestTemplate restTemplate,
        AppProperties props
    ) {
        this.groqChatClient = groqChatClient;
        this.geminiLiteChatClient = geminiLiteChatClient;
        this.geminiFlashChatClient = geminiFlashChatClient;
        this.cerebrasChatClient = cerebrasChatClient.orElse(null);
        this.auditService = auditService;
        this.exchangeRateService = exchangeRateService;
        this.txRepo = txRepo;
        this.categoryRepo = categoryRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.chatSessionRepo = chatSessionRepo;
        this.restTemplate = restTemplate;
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

        // Load conversation history — sliding window of last 6 messages (3 turns)
        List<ChatMessage> history = chatMessageRepo.findTop6BySessionIdOrderByCreatedAtDesc(session.getId())
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

        // Context stuffing — serialize recent transactions into the prompt.
        // Groq is primary (fast inference), Gemini Flash as fallback.
        // Tool-calling was removed: the extra round-trips made responses too slow for chat.
        String reply = advisorWithContextStuffing(userId, question, systemPrompt, historyMessages);

        // Both messages persisted atomically — if the AI call above threw, neither is saved.
        chatMessageRepo.saveAll(List.of(
            ChatMessage.builder().userId(userId).sessionId(session.getId()).role(ChatMessage.ROLE_USER).content(question).build(),
            ChatMessage.builder().userId(userId).sessionId(session.getId()).role(ChatMessage.ROLE_ASSISTANT).content(reply).build()
        ));

        return new AdvisorResponse(reply, session.getId().toString());
    }

    // Social/conversational words that require no financial data in the response
    private static final Set<String> CONVERSATIONAL_WORDS = Set.of(
        "thanks", "thank", "ok", "okay", "got", "see", "sure", "great", "nice",
        "cool", "good", "right", "yes", "no", "hmm", "oh", "ah", "hi", "hello",
        "hey", "bye", "goodbye", "perfect", "understood", "noted", "alright",
        "sounds", "makes", "sense", "course", "awesome", "wow", "lol", "haha"
    );

    /** True if the message is clearly social/conversational — no financial context needed. */
    private boolean isConversational(String question) {
        if (question == null) return false;
        String lower = question.trim().toLowerCase().replaceAll("[^a-z\\s]", "");
        String[] words = lower.split("\\s+");
        if (words.length > 6) return false; // longer messages likely contain a real question
        for (String word : words) {
            if (CONVERSATIONAL_WORDS.contains(word)) return true;
        }
        return false;
    }

    /**
     * Builds a compact aggregated context instead of raw transaction dumps.
     *
     * Best practices applied (Anthropic context engineering guide, 2025):
     * - Aggregated summaries over raw lists — same signal, 80% fewer tokens
     * - Recent transactions verbatim for specific follow-up questions
     * - Monthly breakdown at top (highest-signal for financial chat)
     */
    private String buildCompactContext(List<Transaction> transactions) {
        if (transactions.isEmpty()) return "No transactions recorded yet.";

        String thisMonth = YearMonth.now(ZoneOffset.UTC).toString();
        Map<String, BigDecimal> catExpenses = new TreeMap<>();
        BigDecimal allIncome = BigDecimal.ZERO, allExpenses = BigDecimal.ZERO;
        BigDecimal monthIncome = BigDecimal.ZERO, monthExpenses = BigDecimal.ZERO;

        for (Transaction tx : transactions) {
            String month = tx.getDate().atZone(ZoneOffset.UTC).toLocalDate().toString().substring(0, 7);
            boolean isIncome = "income".equals(tx.getType());
            if (isIncome) {
                allIncome = allIncome.add(tx.getAmount());
                if (month.equals(thisMonth)) monthIncome = monthIncome.add(tx.getAmount());
            } else {
                allExpenses = allExpenses.add(tx.getAmount());
                catExpenses.merge(tx.getCategory(), tx.getAmount(), BigDecimal::add);
                if (month.equals(thisMonth)) monthExpenses = monthExpenses.add(tx.getAmount());
            }
        }

        // Capture finals for use in lambdas
        final BigDecimal totalExpensesFinal = allExpenses;
        final BigDecimal hundred = BigDecimal.valueOf(100);
        BigDecimal monthBalance = monthIncome.subtract(monthExpenses);
        StringBuilder sb = new StringBuilder();
        sb.append("THIS MONTH: income ").append(monthIncome.setScale(2, RoundingMode.HALF_UP))
          .append(" | expenses ").append(monthExpenses.setScale(2, RoundingMode.HALF_UP))
          .append(" | balance ").append(monthBalance.setScale(2, RoundingMode.HALF_UP)).append("\n");
        sb.append("ALL-TIME: income ").append(allIncome.setScale(2, RoundingMode.HALF_UP))
          .append(" | expenses ").append(totalExpensesFinal.setScale(2, RoundingMode.HALF_UP)).append("\n");

        sb.append("\nTOP EXPENSE CATEGORIES:\n");
        catExpenses.entrySet().stream()
            .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
            .limit(6)
            .forEach(e -> {
                BigDecimal pct = totalExpensesFinal.compareTo(BigDecimal.ZERO) > 0
                    ? e.getValue().multiply(hundred).divide(totalExpensesFinal, 0, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
                sb.append("- ").append(e.getKey()).append(": ")
                  .append(e.getValue().setScale(2, RoundingMode.HALF_UP))
                  .append(" (").append(pct).append("%)\n");
            });

        sb.append("\nRECENT TRANSACTIONS (latest 10):\n");
        transactions.stream().limit(10).forEach(tx ->
            sb.append(tx.getDate().atZone(ZoneOffset.UTC).toLocalDate())
              .append(" ").append(tx.getType())
              .append(" ").append(tx.getCategory())
              .append(" ").append(tx.getAmount().toPlainString())
              .append(" — ").append(tx.getDescription()).append("\n")
        );

        return sb.toString();
    }

    /**
     * Advisor context stuffing — redesigned with query routing and aggregated context.
     *
     * Best practices (Anthropic + agenta.ai context engineering, 2025):
     * 1. Route conversational turns to a lightweight path — no DB fetch, no transaction dump
     * 2. For financial queries: aggregated summary + last 10 transactions (not 50 raw lines)
     * 3. Sliding window history (6 messages) already enforced at the call site
     */
    private String advisorWithContextStuffing(UUID userId, String question, String baseSystemPrompt,
                                              List<Message> historyMessages) {
        // Conversational path — no transaction data needed, much faster
        if (isConversational(question)) {
            return callWithFallbackMessages(userId, "advisor-context",
                List.of("groq", "gemini-lite"),
                AdvisorPrompt.SYSTEM, historyMessages, question);
        }

        // Financial path — compact aggregated context instead of raw transaction dump
        List<Transaction> txs = txRepo.findRecentTransactions(userId, PageRequest.of(0, 50));
        String enrichedSystem = baseSystemPrompt + "\n\n" + buildCompactContext(txs);
        return callWithFallbackMessages(userId, "advisor-context",
            List.of("groq", "gemini-flash", "gemini-lite", "cerebras"),
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
                if (result == null || (result instanceof String s && s.isBlank())) {
                    auditService.log(userId, operation, provider,
                        System.currentTimeMillis() - start, false, "null or blank response");
                    log.warn("{} returned null/blank for {}. Trying next provider.", provider, operation);
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
     *
     * Uses RestTemplate directly so we can set an explicit Content-Type on the audio part.
     * Spring AI's AudioTranscriptionPrompt sends ByteArrayResource without a content type,
     * causing some Groq API versions to reject the request. Explicit headers fix this.
     */
    @SuppressWarnings("unchecked")
    public String transcribeAudio(MultipartFile audio, UUID userId) {
        checkDailyLimit(userId);

        // Strip codec parameters — "audio/webm;codecs=opus" → "audio/webm"
        String rawType = audio.getContentType() != null ? audio.getContentType() : "audio/webm";
        String audioMime = rawType.contains(";") ? rawType.substring(0, rawType.indexOf(';')).trim() : rawType;

        String filename = (audio.getOriginalFilename() != null && !audio.getOriginalFilename().isBlank())
            ? audio.getOriginalFilename() : "audio.webm";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + groqApiKey);

        long start = System.currentTimeMillis();
        try {
            // Build multipart body with explicit Content-Type on the file part
            // audio.getBytes() is inside try — IOException is caught and wrapped as TrackAmException
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.parseMediaType(audioMime));
            fileHeaders.setContentDispositionFormData("file", filename);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new HttpEntity<>(audio.getBytes(), fileHeaders));
            body.add("model", "whisper-large-v3-turbo");

            Map<String, Object> response = restTemplate.postForObject(
                "https://api.groq.com/openai/v1/audio/transcriptions",
                new HttpEntity<>(body, headers),
                Map.class
            );
            String transcript = response != null ? (String) response.get("text") : "";
            auditService.log(userId, "transcribe", "groq", System.currentTimeMillis() - start, true, null);
            return transcript != null ? transcript : "";
        } catch (Exception e) {
            auditService.log(userId, "transcribe", "groq", System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("Transcription failed for user {}: {}", userId, e.getClass().getSimpleName());
            throw new TrackAmException("Transcription failed. Please try again.");
        }
    }

    private void checkDailyLimit(UUID userId) {
        if (auditService.isOverDailyLimit(userId, props.getMaxDailyCalls())) {
            throw new TrackAmException("Daily AI call limit reached. Try again tomorrow.");
        }
    }

}
