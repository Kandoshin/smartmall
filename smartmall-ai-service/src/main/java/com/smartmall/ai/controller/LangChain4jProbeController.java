package com.smartmall.ai.controller;

import com.smartmall.ai.service.ProductProbeAssistant;
import com.smartmall.common.Result;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/langchain4j-probe")
public class LangChain4jProbeController {

    private final OpenAiResponsesChatModel chatModel;
    private final OpenAiResponsesStreamingChatModel streamingChatModel;
    private final ProductProbeAssistant productProbeAssistant;

    public LangChain4jProbeController(
            OpenAiResponsesChatModel chatModel,
            OpenAiResponsesStreamingChatModel streamingChatModel,
            ProductProbeAssistant productProbeAssistant) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.productProbeAssistant = productProbeAssistant;
    }

    @GetMapping
    public Result<String> probe() {
        String answer = chatModel.chat("hi");
        return Result.success(answer);
    }

    @GetMapping(
            value = "/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(120_000L);

        streamingChatModel.chat("请用三句话介绍你自己，每句话单独一行",
                new StreamingChatResponseHandler() {

                    @Override
                    public void onPartialResponse(String partialResponse) {
                        try {
                            emitter.send(
                                    SseEmitter.event()
                                                    .name("delta")
                                                    .data(partialResponse)
                            );
                        } catch (IOException exception) {
                            emitter.completeWithError(exception);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        emitter.completeWithError(error);
                    }
                });

        return emitter;
    }

    @GetMapping("/product")
    public Result<String> product(@RequestParam String message) {
        return Result.success(productProbeAssistant.chat(message, new InvocationParameters()));
    }
}
