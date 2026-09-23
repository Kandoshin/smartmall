package com.smartmall.ai.service;

import com.smartmall.ai.tool.OrderTools;
import com.smartmall.ai.exception.AiUpstreamException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShoppingGraphStreamTest {

    private final ProductProbeAssistant assistant = mock(ProductProbeAssistant.class);

    @Test
    void forwardsToolStatusAndDeltasWithOnlyTheCurrentToken() throws Exception {
        TokenStream tokenStream = mock(TokenStream.class);
        when(assistant.stream(eq("查看我的订单"), any(InvocationParameters.class)))
                .thenAnswer(invocation -> {
                    InvocationParameters parameters = invocation.getArgument(1);
                    assertEquals("access-token", parameters.get(OrderTools.ACCESS_TOKEN_KEY));
                    return tokenStream;
                });
        AtomicReference<Consumer<String>> partial = new AtomicReference<>();
        AtomicReference<Consumer<BeforeToolExecution>> beforeTool = new AtomicReference<>();
        AtomicReference<Consumer<ChatResponse>> complete = new AtomicReference<>();
        when(tokenStream.onPartialResponse(any())).thenAnswer(invocation -> {
            partial.set(invocation.getArgument(0));
            return tokenStream;
        });
        when(tokenStream.beforeToolExecution(any())).thenAnswer(invocation -> {
            beforeTool.set(invocation.getArgument(0));
            return tokenStream;
        });
        when(tokenStream.onCompleteResponse(any())).thenAnswer(invocation -> {
            complete.set(invocation.getArgument(0));
            return tokenStream;
        });
        when(tokenStream.onError(any())).thenReturn(tokenStream);

        List<String> events = new ArrayList<>();
        ChatStreamSink sink = new ChatStreamSink() {
            public void delta(String text) { events.add("delta:" + text); }
            public void status(String text) { events.add("status:" + text); }
            public boolean isOpen() { return true; }
        };
        doAnswer(invocation -> {
            beforeTool.get().accept(BeforeToolExecution.builder()
                    .request(ToolExecutionRequest.builder().id("call-1")
                            .name("listMyOrders").arguments("{}").build()).build());
            partial.get().accept("你有 ");
            partial.get().accept("1 个订单");
            complete.get().accept(ChatResponse.builder().aiMessage(AiMessage.from("你有 1 个订单")).build());
            return null;
        }).when(tokenStream).start();

        new ShoppingGraphService(assistant).runStream("查看我的订单", "access-token", sink);

        assertEquals(List.of("status:正在查询你的订单…", "delta:你有 ", "delta:1 个订单"), events);
    }

    @Test
    void propagatesStreamFailure() throws Exception {
        TokenStream tokenStream = mock(TokenStream.class);
        when(assistant.stream(eq("你好"), any(InvocationParameters.class))).thenReturn(tokenStream);
        when(tokenStream.onPartialResponse(any())).thenReturn(tokenStream);
        when(tokenStream.beforeToolExecution(any())).thenReturn(tokenStream);
        when(tokenStream.onCompleteResponse(any())).thenReturn(tokenStream);
        AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        when(tokenStream.onError(any())).thenAnswer(invocation -> {
            error.set(invocation.getArgument(0));
            return tokenStream;
        });
        doAnswer(invocation -> {
            error.get().accept(new IllegalStateException("上游失败"));
            return null;
        }).when(tokenStream).start();
        ChatStreamSink sink = mock(ChatStreamSink.class);

        assertEquals("AI 服务暂时不可用，请稍后重试", assertThrows(AiUpstreamException.class,
                () -> new ShoppingGraphService(assistant).runStream("你好", "token", sink))
                .getMessage());
    }
}
