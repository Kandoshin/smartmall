package com.smartmall.ai.service;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import com.smartmall.ai.state.ShoppingState;
import org.springframework.stereotype.Service;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Service
public class ShoppingGraphService {

    private final CompiledGraph<ShoppingState> graph;

    public ShoppingGraphService(ProductProbeAssistant assistant)
            throws GraphStateException {
        this.graph = new StateGraph<>(ShoppingState::new)
                .addNode("shopping_agent", node_async(state -> {
                    String message = state.message();

                    String answer = assistant.chat(message);

                    return Map.of(ShoppingState.ANSWER_KEY, answer);
                }))
                .addEdge(START, "shopping_agent")
                .addEdge("shopping_agent", END)
                .compile();
    }

    public String run(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        ShoppingState finalState = graph.invoke(
                        GraphInput.args(Map.of(ShoppingState.MESSAGE_KEY, message)),
                        RunnableConfig.empty()
                )
                .orElseThrow(() ->
                        new IllegalStateException("图没有产生最终状态"));

        return finalState.answer()
                .orElse("AI 没有返回回答");
    }
}
