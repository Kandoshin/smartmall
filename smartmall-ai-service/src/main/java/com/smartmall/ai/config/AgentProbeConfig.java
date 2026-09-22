package com.smartmall.ai.config;

import com.smartmall.ai.service.ProductProbeAssistant;
import com.smartmall.ai.tool.ProductTools;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentProbeConfig {

    @Bean
    public ProductProbeAssistant productProbeAssistant(
            OpenAiResponsesChatModel chatModel,
            ProductTools productTools) {

        return AiServices.builder(ProductProbeAssistant.class)
                .chatModel(chatModel)
                .tools(productTools)
                .maxToolCallingRoundTrips(3)
                .build();
    }
}
