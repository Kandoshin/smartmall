package com.smartmall.ai.exception;

public class AiUpstreamException extends RuntimeException {

    public AiUpstreamException(String message) {
        super(message);
    }

    public AiUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
