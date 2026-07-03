package com.trackam.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Multi-provider AI configuration.
 *
 * Spring Boot auto-configures Groq as the @Primary bean (the default when a bare
 * ChatClient is injected). The per-operation fallback ORDER is defined at the call
 * sites in AiService.callWithFallback, not by @Primary. Gemini and Cerebras beans are
 * derived from the auto-configured base using mutate(), same retry and serialization
 * logic, different base URL and API key.
 *
 * Per-operation fallback order (see AiService):
 *  - text parsing → gemini-lite, then groq, then gemini-flash, then cerebras
 *  - vision       → groq (Llama 4 Scout), then gemini-flash
 *  - advisor      → groq, then gemini-flash, then gemini-lite, then cerebras
 *  - insight      → gemini-lite, then groq, then gemini-flash
 *
 * Free tiers: groq and gemini-lite ~1,000 RPD, cerebras ~1M TPD.
 */
@Configuration
@RequiredArgsConstructor
public class AiConfig {

    private final AppProperties props;

    /** Spring @Primary bean (default injection) — Groq (Llama 4 Scout), auto-configured from spring.ai.openai.*. */
    @Bean
    @Primary
    public ChatClient groqChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel).build();
    }

    /** Gemini 2.5 Flash-Lite — fast, cheap text parsing. */
    @Bean
    public ChatClient geminiLiteChatClient(OpenAiApi baseOpenAiApi, OpenAiChatModel baseChatModel) {
        OpenAiApi geminiApi = baseOpenAiApi.mutate()
            .baseUrl(props.getGeminiBaseUrl())
            .apiKey(props.getGeminiApiKey())
            .build();
        OpenAiChatModel model = baseChatModel.mutate()
            .openAiApi(geminiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(props.getGeminiTextModel())
                .build())
            .build();
        return ChatClient.builder(model).build();
    }

    /** Gemini 2.5 Flash — complex reasoning, advisor chat fallback. */
    @Bean
    public ChatClient geminiFlashChatClient(OpenAiApi baseOpenAiApi, OpenAiChatModel baseChatModel) {
        OpenAiApi geminiApi = baseOpenAiApi.mutate()
            .baseUrl(props.getGeminiBaseUrl())
            .apiKey(props.getGeminiApiKey())
            .build();
        OpenAiChatModel model = baseChatModel.mutate()
            .openAiApi(geminiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(props.getGeminiComplexModel())
                .build())
            .build();
        return ChatClient.builder(model).build();
    }

    /** Cerebras — ultra-fast text-only fallback. Only created if API key is configured. */
    @Bean
    @ConditionalOnProperty(name = "trackam.ai.cerebras.api-key", matchIfMissing = false)
    public ChatClient cerebrasChatClient(OpenAiApi baseOpenAiApi, OpenAiChatModel baseChatModel) {
        OpenAiApi cerebrasApi = baseOpenAiApi.mutate()
            .baseUrl(props.getCerebrasBaseUrl())
            .apiKey(props.getCerebrasApiKey())
            .build();
        OpenAiChatModel model = baseChatModel.mutate()
            .openAiApi(cerebrasApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(props.getCerebrasModel())
                .build())
            .build();
        return ChatClient.builder(model).build();
    }
}
