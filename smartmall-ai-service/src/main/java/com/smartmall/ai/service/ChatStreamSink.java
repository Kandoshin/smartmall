package com.smartmall.ai.service;

public interface ChatStreamSink {

    void delta(String text);

    void status(String text);

    boolean isOpen();
}
