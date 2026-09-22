package com.smartmall.ai.service;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Service;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Service
public class GraphProbeService {

    private final CompiledGraph<AgentState> graph;

    public GraphProbeService() throws GraphStateException {
        this.graph = new StateGraph<>(AgentState::new)
                .addNode("prepare", node_async(state ->
                        Map.of("step", "准备完成")))
                .addNode("finish", node_async(state ->
                        Map.of(
                                "result",
                                state.<String>value("step")
                                        .orElse("没有准备结果")
                                        + "，流程结束"
                        )))
                .addEdge(START, "prepare")
                .addEdge("prepare", "finish")
                .addEdge("finish", END)
                .compile();
    }

    public String run() {
        AgentState finalState = graph.invoke(
                        GraphInput.noArgs(),
                        RunnableConfig.empty()
                )
                .orElseThrow(() ->
                        new IllegalStateException("图没有产生最终状态"));

        return finalState.<String>value("result")
                .orElse("没有运行结果");
    }
}
