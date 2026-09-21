package com.smartmall.ai.service;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.services.blocking.ResponseService;
import com.smartmall.ai.exception.AiUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceTest {

    private OpenAIClient openAIClient;
    private ResponseService responseService;
    private CommerceToolService commerceToolService;
    private AiService aiService;

    @BeforeEach
    void setUp() {
        openAIClient = mock(OpenAIClient.class);
        responseService = mock(ResponseService.class);
        commerceToolService = mock(CommerceToolService.class);
        when(openAIClient.responses()).thenReturn(responseService);
        aiService = new AiService(openAIClient, commerceToolService, "test-model");
    }

    @Test
    void forwardsTextDeltasInOrderAndClosesTheUpstreamStream() {
        StreamResponse<ResponseStreamEvent> stream = streamOf(
                deltaEvent("你"),
                deltaEvent("好"),
                completedEvent(responseWithOutput(List.of())));
        when(responseService.createStreaming(any(ResponseCreateParams.class))).thenReturn(stream);
        RecordingSink sink = new RecordingSink();

        aiService.chatStream("你好", "access-token", sink);

        assertThat(sink.deltas).containsExactly("你", "好");
        assertThat(sink.statuses).isEmpty();
        verify(stream).close();
    }

    @Test
    void executesOrderToolThenContinuesStreamingTheSecondResponse() {
        ResponseFunctionToolCall toolCall = mock(ResponseFunctionToolCall.class);
        when(toolCall.name()).thenReturn("list_my_orders");
        when(toolCall.callId()).thenReturn("call-1");

        ResponseOutputItem functionCallItem = mock(ResponseOutputItem.class);
        when(functionCallItem.isFunctionCall()).thenReturn(true);
        when(functionCallItem.asFunctionCall()).thenReturn(toolCall);

        StreamResponse<ResponseStreamEvent> firstStream = streamOf(
                completedEvent(responseWithOutput(List.of(functionCallItem))));
        StreamResponse<ResponseStreamEvent> secondStream = streamOf(
                deltaEvent("你有 1 个订单"),
                completedEvent(responseWithOutput(List.of())));
        when(responseService.createStreaming(any(ResponseCreateParams.class)))
                .thenReturn(firstStream, secondStream);
        when(commerceToolService.listMyOrders("access-token"))
                .thenReturn("[{\"id\":1}]");
        RecordingSink sink = new RecordingSink();

        aiService.chatStream("查看我的订单", "access-token", sink);

        assertThat(sink.statuses).containsExactly("正在查询你的订单…");
        assertThat(sink.deltas).containsExactly("你有 1 个订单");
        verify(commerceToolService).listMyOrders("access-token");
        verify(responseService, times(2))
                .createStreaming(any(ResponseCreateParams.class));
        verify(firstStream).close();
        verify(secondStream).close();
    }

    @Test
    void rejectsAStreamThatEndsWithoutCompletedEvent() {
        StreamResponse<ResponseStreamEvent> stream = streamOf(deltaEvent("部分回答"));
        when(responseService.createStreaming(any(ResponseCreateParams.class))).thenReturn(stream);

        assertThatThrownBy(() -> aiService.chatStream(
                "你好", "access-token", new RecordingSink()))
                .isInstanceOf(AiUpstreamException.class)
                .hasMessage("AI 流式响应未正常完成");
        verify(stream).close();
    }

    @Test
    void mapsFailedStreamEventToAnUpstreamException() {
        ResponseStreamEvent failedEvent = mock(ResponseStreamEvent.class);
        when(failedEvent.isFailed()).thenReturn(true);
        StreamResponse<ResponseStreamEvent> stream = streamOf(failedEvent);
        when(responseService.createStreaming(any(ResponseCreateParams.class))).thenReturn(stream);

        assertThatThrownBy(() -> aiService.chatStream(
                "你好", "access-token", new RecordingSink()))
                .isInstanceOf(AiUpstreamException.class)
                .hasMessage("AI 流式响应异常结束");
        verify(stream).close();
    }

    private ResponseStreamEvent deltaEvent(String text) {
        ResponseTextDeltaEvent delta = mock(ResponseTextDeltaEvent.class);
        when(delta.delta()).thenReturn(text);
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isOutputTextDelta()).thenReturn(true);
        when(event.asOutputTextDelta()).thenReturn(delta);
        return event;
    }

    private ResponseStreamEvent completedEvent(Response response) {
        ResponseCompletedEvent completed = mock(ResponseCompletedEvent.class);
        when(completed.response()).thenReturn(response);
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isCompleted()).thenReturn(true);
        when(event.asCompleted()).thenReturn(completed);
        return event;
    }

    private Response responseWithOutput(List<ResponseOutputItem> output) {
        Response response = mock(Response.class);
        when(response.output()).thenReturn(output);
        return response;
    }

    @SafeVarargs
    private final StreamResponse<ResponseStreamEvent> streamOf(ResponseStreamEvent... events) {
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> stream = mock(StreamResponse.class);
        when(stream.stream()).thenReturn(Stream.of(events));
        return stream;
    }

    private static final class RecordingSink implements ChatStreamSink {
        private final List<String> deltas = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();

        @Override
        public void delta(String text) {
            deltas.add(text);
        }

        @Override
        public void status(String text) {
            statuses.add(text);
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }
}
