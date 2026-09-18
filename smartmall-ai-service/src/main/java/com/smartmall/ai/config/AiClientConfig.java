package com.smartmall.ai.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AiClientConfig {

    @Bean
    public OpenAIClient openAIClient(
            @Value("${smartmall.ai.base-url}") String baseUrl,
            @Value("${smartmall.ai.api-key}") String apiKey,
            @Value("${smartmall.ai.user-agent}") String userAgent) {

        SpringAiOpenAiHttpClient httpClient =
                SpringAiOpenAiHttpClient.builder()
                        .timeout(Duration.ofSeconds(60))
                        .build();

        ClientOptions clientOptions =
                ClientOptions.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .putHeader("User-Agent",userAgent)
                        .httpClient(httpClient)
                        .maxRetries(0)
                        .build();

        return new OpenAIClientImpl(clientOptions);
    }
}
