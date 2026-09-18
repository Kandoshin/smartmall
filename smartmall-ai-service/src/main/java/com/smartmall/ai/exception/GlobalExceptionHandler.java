package com.smartmall.ai.exception;

import com.smartmall.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(
            MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldErrors().get(0);
        return ResponseEntity.badRequest()
                .body(Result.failure(400, fieldError.getDefaultMessage()));
    }

    @ExceptionHandler(AiUpstreamException.class)
    public ResponseEntity<Result<Void>> handleAiUpstreamException(
            AiUpstreamException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.failure(503, exception.getMessage()));
    }
}
