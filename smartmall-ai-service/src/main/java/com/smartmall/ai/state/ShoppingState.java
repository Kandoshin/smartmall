package com.smartmall.ai.state;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.Optional;

public class ShoppingState extends AgentState {

    public static final String MESSAGE_KEY = "message";
    public static final String ANSWER_KEY = "answer";

    public ShoppingState(Map<String, Object> initialData) {
        super(initialData);
    }

    public String message() {
        return this.<String>value(MESSAGE_KEY)
                .orElseThrow(() -> new IllegalStateException("缺少用户消息"));
    }

    public Optional<String> answer() {
        return this.<String>value(ANSWER_KEY);
    }
}
