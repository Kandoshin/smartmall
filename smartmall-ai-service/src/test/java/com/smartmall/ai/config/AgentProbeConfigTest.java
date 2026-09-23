package com.smartmall.ai.config;

import com.smartmall.ai.service.CommerceToolService;
import com.smartmall.ai.service.ProductProbeAssistant;
import com.smartmall.ai.tool.OrderTools;
import com.smartmall.ai.tool.ProductTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentProbeConfigTest {

    @Test
    void registeredOrderToolReceivesTokenWithoutExposingItToModel() {
        OpenAiResponsesChatModel model = mock(OpenAiResponsesChatModel.class);
        CommerceToolService commerceToolService = mock(CommerceToolService.class);
        ProductProbeAssistant assistant = new AgentProbeConfig().productProbeAssistant(
                model,
                mock(OpenAiResponsesStreamingChatModel.class),
                new ProductTools(commerceToolService),
                new OrderTools(commerceToolService));
        ToolExecutionRequest orderCall = ToolExecutionRequest.builder()
                .id("call-1")
                .name("listMyOrders")
                .arguments("{}")
                .build();
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(orderCall)).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("订单结果")).build());
        when(commerceToolService.listMyOrders("secret-token")).thenReturn("[]");

        assertEquals("订单结果", assistant.chat(
                "查看我的订单",
                InvocationParameters.from(OrderTools.ACCESS_TOKEN_KEY, "secret-token")));

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(requests.capture());
        ChatRequest firstRequest = requests.getAllValues().get(0);
        List<String> toolNames = firstRequest.toolSpecifications().stream()
                .map(specification -> specification.name())
                .toList();
        assertTrue(toolNames.containsAll(List.of("searchProducts", "listMyOrders")));
        assertFalse(firstRequest.toString().contains("secret-token"));
        verify(commerceToolService).listMyOrders("secret-token");
    }
}
