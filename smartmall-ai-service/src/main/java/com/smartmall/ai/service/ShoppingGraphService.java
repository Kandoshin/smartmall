package com.smartmall.ai.service;

import com.smartmall.ai.state.ShoppingState;
import com.smartmall.ai.tool.OrderTools;
import com.smartmall.ai.exception.AiUpstreamException;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.TokenStream;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

@Service
public class ShoppingGraphService {

    private static final String STREAM_SINK_KEY = "streamSink";
    private final CompiledGraph<ShoppingState> graph;

    public ShoppingGraphService(ProductProbeAssistant assistant)
            throws GraphStateException {
        this.graph = new StateGraph<>(ShoppingState::new)
                .addNode("shopping_agent", node_async((state, config) -> {
                    String message = state.message();
                    InvocationParameters parameters = new InvocationParameters();
                    config.metadata(OrderTools.ACCESS_TOKEN_KEY)
                            .ifPresent(token -> parameters.put(
                                    OrderTools.ACCESS_TOKEN_KEY, (String) token));

                    Object streamSink = config.metadata(STREAM_SINK_KEY).orElse(null);
                    String answer = streamSink instanceof ChatStreamSink sink
                            ? stream(assistant, message, parameters, sink)
                            : assistant.chat(message, parameters);

                    return Map.of(ShoppingState.ANSWER_KEY, answer);
                }))
                .addEdge(START, "shopping_agent")
                .addEdge("shopping_agent", END)
                .compile();
    }

    public String run(String message, String accessToken) {
        return invoke(message, accessToken, null);
    }

    public void runStream(String message, String accessToken, ChatStreamSink sink) {
        invoke(message, accessToken, sink);
    }

    private String invoke(String message, String accessToken, ChatStreamSink sink) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        RunnableConfig.Builder config = RunnableConfig.builder();
        if (accessToken != null && !accessToken.isBlank()) {
            config.putMetadata(OrderTools.ACCESS_TOKEN_KEY, accessToken);
        }
        if (sink != null) {
            config.putMetadata(STREAM_SINK_KEY, sink);
        }

        ShoppingState finalState = graph.invoke(
                        GraphInput.args(Map.of(ShoppingState.MESSAGE_KEY, message)),
                        config.build()
                )
                .orElseThrow(() ->
                        new IllegalStateException("图没有产生最终状态"));

        return finalState.answer()
                .orElse("AI 没有返回回答");
    }

    private String stream(
            ProductProbeAssistant assistant,
            String message,
            InvocationParameters parameters,
            ChatStreamSink sink) {
        CompletableFuture<String> completed = new CompletableFuture<>();
        TokenStream stream = assistant.stream(message, parameters)
                .onPartialResponse(delta -> {
                    if (!sink.isOpen()) {
                        throw disconnect(completed);
                    }
                    try {
                        sink.delta(delta);
                    } catch (RuntimeException exception) {
                        completed.completeExceptionally(exception);
                        throw exception;
                    }
                })
                .beforeToolExecution(tool -> {
                    if (!sink.isOpen()) {
                        throw disconnect(completed);
                    }
                    String status = switch (tool.request().name()) {
                        case "searchProducts" -> "正在查询商品…";
                        case "listMyOrders" -> "正在查询你的订单…";
                        default -> "正在处理请求…";
                    };
                    try {
                        sink.status(status);
                    } catch (RuntimeException exception) {
                        completed.completeExceptionally(exception);
                        throw exception;
                    }
                })
                .onCompleteResponse(response -> {
                    String answer = response.aiMessage().text();
                    if (answer == null || answer.isBlank()) {
                        completed.completeExceptionally(new AiUpstreamException("AI 没有返回回答"));
                    } else {
                        completed.complete(answer);
                    }
                })
                .onError(completed::completeExceptionally);
        stream.start();
        try {
            return completed.get(120, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiUpstreamException("AI 请求已中断", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof AiUpstreamException upstreamException) {
                throw upstreamException;
            }
            throw new AiUpstreamException("AI 服务暂时不可用，请稍后重试", exception.getCause());
        } catch (TimeoutException exception) {
            throw new AiUpstreamException("AI 响应超时", exception);
        }
    }

    private AiUpstreamException disconnect(CompletableFuture<String> completed) {
        AiUpstreamException exception = new AiUpstreamException("客户端已断开连接");
        completed.completeExceptionally(exception);
        return exception;
    }
}
