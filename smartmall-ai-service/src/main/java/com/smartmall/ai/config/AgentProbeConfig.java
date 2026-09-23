package com.smartmall.ai.config;

import com.smartmall.ai.service.ProductProbeAssistant;
import com.smartmall.ai.tool.OrderTools;
import com.smartmall.ai.tool.ProductTools;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentProbeConfig {

    @Bean
    public ProductProbeAssistant productProbeAssistant(
            OpenAiResponsesChatModel chatModel,
            OpenAiResponsesStreamingChatModel streamingChatModel,
            ProductTools productTools,
            OrderTools orderTools) {

        return AiServices.builder(ProductProbeAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .tools(productTools, orderTools)
                .maxToolCallingRoundTrips(3)
                .build();
    }
}
