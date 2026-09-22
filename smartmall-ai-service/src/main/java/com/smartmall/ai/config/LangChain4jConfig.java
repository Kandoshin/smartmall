package com.smartmall.ai.config;

import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

@Configuration
public class LangChain4jConfig {

    @Bean
    public OpenAiResponsesStreamingChatModel langChain4jStreamingChatModel(
            @Value("${smartmall.ai.base-url}") String baseUrl,
            @Value("${smartmall.ai.api-key}") String apiKey,
            @Value("${smartmall.ai.model}") String model,
            @Value("${smartmall.ai.user-agent}") String userAgent) {

        return OpenAiResponsesStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .customHeaders(Map.of("User-Agent", userAgent))
                .store(false)
                .build();
    }


    @Bean
    public OpenAiResponsesChatModel langChain4jChatModel(
            @Value("${smartmall.ai.base-url}") String baseUrl,
            @Value("${smartmall.ai.api-key}") String apiKey,
            @Value("${smartmall.ai.model}") String model,
            @Value("${smartmall.ai.user-agent}") String userAgent) {

        return OpenAiResponsesChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .customHeaders(Map.of("User-Agent", userAgent))
                .store(false)
                .build();
    }
}
