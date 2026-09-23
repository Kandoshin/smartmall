package com.smartmall.ai.controller;

import com.smartmall.ai.dto.ChatRequest;
import com.smartmall.ai.dto.ChatStreamEvent;
import com.smartmall.ai.service.ShoppingGraphService;
import com.smartmall.ai.exception.AiUpstreamException;
import com.smartmall.ai.service.ChatStreamSink;
import com.smartmall.common.Result;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final long STREAM_TIMEOUT_MILLIS = 120_000L;

    private final ShoppingGraphService shoppingGraphService;
    private final Executor chatExecutor;

    public ChatController(
            ShoppingGraphService shoppingGraphService,
            @Qualifier("chatExecutor") Executor chatExecutor) {
        this.shoppingGraphService = shoppingGraphService;
        this.chatExecutor = chatExecutor;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        EmitterChatStreamSink sink = new EmitterChatStreamSink(emitter);

        emitter.onCompletion(sink::close);
        emitter.onTimeout(sink::close);
        emitter.onError(error -> sink.close());

        chatExecutor.execute(() -> streamChat(
                request.message(), jwt.getTokenValue(), emitter, sink));
        return emitter;
    }

    private void streamChat(
            String message,
            String accessToken,
            SseEmitter emitter,
            EmitterChatStreamSink sink) {
        try {
            shoppingGraphService.runStream(message, accessToken, sink);
            if (sink.isOpen()) {
                sink.send("done", Result.success(new ChatStreamEvent("")));
                emitter.complete();
            }
        } catch (StreamDisconnectedException exception) {
            // The browser has gone away; stop sending downstream events.
        } catch (RuntimeException exception) {
            if (sink.isOpen()) {
                String errorMessage = exception instanceof AiUpstreamException
                        ? exception.getMessage()
                        : "AI 服务暂时不可用，请稍后重试";
                try {
                    sink.send("error", Result.failure(503, errorMessage));
                    emitter.complete();
                } catch (StreamDisconnectedException ignored) {
                    // The client disconnected while the error event was being sent.
                }
            }
        }
    }

    private static final class EmitterChatStreamSink implements ChatStreamSink {
        private final SseEmitter emitter;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private EmitterChatStreamSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void delta(String text) {
            send("delta", Result.success(new ChatStreamEvent(text)));
        }

        @Override
        public void status(String text) {
            send("status", Result.success(new ChatStreamEvent(text)));
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        private void close() {
            open.set(false);
        }

        private void send(String eventName, Result<?> result) {
            if (!open.get()) {
                throw new StreamDisconnectedException();
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(result, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException exception) {
                open.set(false);
                throw new StreamDisconnectedException(exception);
            }
        }
    }

    private static final class StreamDisconnectedException extends RuntimeException {
        private StreamDisconnectedException() {
        }

        private StreamDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
