package com.smartmall.ai.controller;

import com.smartmall.ai.service.ProductProbeAssistant;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChain4jProbeControllerTest {

    @Test
    void publicProductProbeDoesNotSupplyCredentials() {
        ProductProbeAssistant assistant = mock(ProductProbeAssistant.class);
        LangChain4jProbeController controller = new LangChain4jProbeController(
                mock(OpenAiResponsesChatModel.class),
                mock(OpenAiResponsesStreamingChatModel.class),
                assistant);
        ArgumentCaptor<InvocationParameters> parameters =
                ArgumentCaptor.forClass(InvocationParameters.class);
        when(assistant.chat(org.mockito.ArgumentMatchers.eq("键盘"),
                org.mockito.ArgumentMatchers.any(InvocationParameters.class)))
                .thenReturn("商品结果");

        assertEquals("商品结果", controller.product("键盘").getData());
        verify(assistant).chat(org.mockito.ArgumentMatchers.eq("键盘"), parameters.capture());
        assertTrue(parameters.getValue().asMap().isEmpty());
    }
}
