package com.trackam.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Multi-provider AI configuration.
 *
 * Spring Boot auto-configures Groq as the primary provider via spring.ai.openai.*.
 * Gemini beans are derived from the auto-configured base using mutate() — same
 * retry/serialization logic, different base URL and API key.
 *
 * Provider routing (see AiService):
 *  - groq          → vision + fast text (Llama 4 Scout, 14,400 req/day free)
 *  - gemini-lite   → text parsing (Gemini 2.5 Flash-Lite, 1,000 req/day free)
 *  - gemini-flash  → advisor + complex (Gemini 2.5 Flash, 250 req/day free)
 */
@Configuration
@RequiredArgsConstructor
public class AiConfig {

    private final AppProperties props;

    /** Primary — Groq (Llama 4 Scout). Auto-configured from spring.ai.openai.*. */
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

    /** Gemini 2.5 Flash — complex reasoning, RAG advisor. */
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
}
