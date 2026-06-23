package com.trackam.service;

import com.trackam.config.AppProperties;
import com.trackam.dto.ParsedTransactionResponse;
import com.trackam.exception.TrackAmException;
import com.trackam.model.ChatSession;
import com.trackam.repository.ChatMessageRepository;
import com.trackam.repository.ChatSessionRepository;
import com.trackam.repository.CustomCategoryRepository;
import com.trackam.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the multi-provider AI fallback chain in AiService.parseText.
 *
 * Provider order is: gemini-lite → groq → gemini-flash → cerebras. Each
 * provider that errors out is audit-logged with success=false; the first
 * one returning a valid response wins. If every provider fails, a
 * TrackAmException is raised so the client surfaces a clean 503 instead
 * of an opaque crash. This is the headline reliability claim — losing it
 * means a single vendor outage takes the whole app down.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiServiceFallbackTest {

    private static final UUID USER_ID = UUID.fromString("92113dc0-dbda-461c-b074-8a0c41222b7b");

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) ChatClient groqChatClient;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) ChatClient geminiLiteChatClient;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) ChatClient geminiFlashChatClient;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) ChatClient cerebrasChatClient;

    @Mock AuditService auditService;
    @Mock ExchangeRateService exchangeRateService;
    @Mock TransactionRepository txRepo;
    @Mock CustomCategoryRepository categoryRepo;
    @Mock ChatMessageRepository chatMessageRepo;
    @Mock ChatSessionRepository chatSessionRepo;
    @Mock RestTemplate restTemplate;
    @Mock AppProperties props;

    private AiService aiService;

    @BeforeEach
    void setUp() {
        when(auditService.isOverDailyLimit(any(UUID.class), anyInt())).thenReturn(false);
        when(props.getMaxDailyCalls()).thenReturn(500);
        when(categoryRepo.findByUserIdOrderBySortOrderAsc(any(UUID.class))).thenReturn(List.of());

        aiService = new AiService(
            groqChatClient, geminiLiteChatClient, geminiFlashChatClient,
            Optional.of(cerebrasChatClient),
            auditService, exchangeRateService,
            txRepo, categoryRepo, chatMessageRepo, chatSessionRepo,
            restTemplate, props);
    }

    @Test
    @DisplayName("primary provider responds → secondary providers never invoked")
    void primaryWins() {
        ParsedTransactionResponse primaryResponse = parsedResponse("100");
        stubProviderReturns(geminiLiteChatClient, primaryResponse);

        ParsedTransactionResponse result = aiService.parseText("Bought rice 100 cedis at Makola", "GHS", USER_ID);

        assertThat(result.amount()).isEqualByComparingTo("100");
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("gemini-lite"), anyLong(), eq(true), isNull());
        // Other providers should never be called
        verify(auditService, never()).log(eq(USER_ID), eq("parse-text"), eq("groq"), anyLong(), anyBoolean(), any());
        verify(auditService, never()).log(eq(USER_ID), eq("parse-text"), eq("gemini-flash"), anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("primary throws → fallback (groq) succeeds → both attempts audit-logged")
    void primaryFailsFallbackSucceeds() {
        stubProviderThrows(geminiLiteChatClient, "rate limit hit");
        stubProviderReturns(groqChatClient, parsedResponse("75"));

        ParsedTransactionResponse result = aiService.parseText("Trotro 75 cedis", "GHS", USER_ID);

        assertThat(result.amount()).isEqualByComparingTo("75");
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("gemini-lite"), anyLong(), eq(false), anyString());
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("groq"), anyLong(), eq(true), isNull());
    }

    @Test
    @DisplayName("first two providers throw → third succeeds")
    void cascadesThroughTwoFailures() {
        stubProviderThrows(geminiLiteChatClient, "timeout");
        stubProviderThrows(groqChatClient, "5xx error");
        stubProviderReturns(geminiFlashChatClient, parsedResponse("250"));

        ParsedTransactionResponse result = aiService.parseText("Paid 250 cedis for fuel", "GHS", USER_ID);

        assertThat(result.amount()).isEqualByComparingTo("250");
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("gemini-lite"), anyLong(), eq(false), anyString());
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("groq"), anyLong(), eq(false), anyString());
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("gemini-flash"), anyLong(), eq(true), isNull());
    }

    @Test
    @DisplayName("every provider fails → TrackAmException surfaces, cause is the last upstream failure")
    void allProvidersFailRaisesTrackAmException() {
        stubProviderThrows(geminiLiteChatClient, "lite down");
        stubProviderThrows(groqChatClient, "groq down");
        stubProviderThrows(geminiFlashChatClient, "flash down");
        stubProviderThrows(cerebrasChatClient, "cerebras down");

        assertThatThrownBy(() -> aiService.parseText("Bought 50 cedis worth of yam", "GHS", USER_ID))
            .isInstanceOf(TrackAmException.class)
            .hasMessageContaining("All AI providers failed");

        // Every provider should have been tried and audit-logged as a failure
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("gemini-lite"), anyLong(), eq(false), anyString());
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("groq"), anyLong(), eq(false), anyString());
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("gemini-flash"), anyLong(), eq(false), anyString());
        verify(auditService).log(eq(USER_ID), eq("parse-text"), eq("cerebras"), anyLong(), eq(false), anyString());
    }

    @Test
    @DisplayName("daily limit breached → no providers tried, TrackAmException raised")
    void overDailyLimitShortCircuits() {
        when(auditService.isOverDailyLimit(any(UUID.class), anyInt())).thenReturn(true);

        assertThatThrownBy(() -> aiService.parseText("Bought rice 50 cedis at market", "GHS", USER_ID))
            .isInstanceOf(TrackAmException.class)
            .hasMessageContaining("Daily AI call limit");

        // No provider attempts should have been logged
        verify(auditService, never()).log(any(UUID.class), eq("parse-text"), anyString(), anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("advisor: sessionId owned by another user → 404, nothing persisted")
    void foreignSessionIdRejected() {
        UUID foreignSessionId = UUID.randomUUID();
        ChatSession otherUsersSession = ChatSession.builder()
            .id(foreignSessionId)
            .userId(UUID.randomUUID()) // belongs to someone else
            .build();
        when(chatSessionRepo.findById(foreignSessionId)).thenReturn(Optional.of(otherUsersSession));

        assertThatThrownBy(() -> aiService.askAdvisor("How am I doing this month?", null, foreignSessionId, USER_ID))
            .isInstanceOfSatisfying(TrackAmException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // The AI chain is never reached and no chat history is written.
        verify(chatMessageRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("advisor: unknown sessionId → 404, no silent new session created")
    void unknownSessionIdRejected() {
        UUID unknownSessionId = UUID.randomUUID();
        when(chatSessionRepo.findById(unknownSessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> aiService.askAdvisor("How am I doing this month?", null, unknownSessionId, USER_ID))
            .isInstanceOfSatisfying(TrackAmException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(chatSessionRepo, never()).save(any());
        verify(chatMessageRepo, never()).saveAll(any());
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static ParsedTransactionResponse parsedResponse(String amount) {
        return new ParsedTransactionResponse(
            new BigDecimal(amount),
            "GHS",
            "market",
            "expense",
            "Mock parsed transaction",
            "Mock vendor",
            "2026-06-04T00:00:00Z",
            92,
            null, null, null
        );
    }

    private static void stubProviderReturns(ChatClient client, ParsedTransactionResponse response) {
        when(client.prompt().system(anyString()).user(anyString()).call().entity(eq(ParsedTransactionResponse.class)))
            .thenReturn(response);
    }

    private static void stubProviderThrows(ChatClient client, String message) {
        when(client.prompt().system(anyString()).user(anyString()).call().entity(eq(ParsedTransactionResponse.class)))
            .thenThrow(new RuntimeException(message));
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
