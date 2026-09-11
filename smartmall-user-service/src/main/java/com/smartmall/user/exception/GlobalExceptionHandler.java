package com.smartmall.user.exception;

import com.smartmall.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.oauth2.jwt.BadJwtException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Invalid refresh credentials are authentication failures, not leaked decoder details.
    // Do not catch all JwtException: signing/infrastructure failures are server errors.
    @ExceptionHandler(BadJwtException.class)
    public ResponseEntity<Result<Void>> handleBadJwt(BadJwtException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.failure(401, "登录已失效，请重新登录"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgumentException(
            IllegalArgumentException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(400, exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(
            MethodArgumentNotValidException exception) {

        FieldError fieldError =
                exception.getBindingResult().getFieldErrors().get(0);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(400, fieldError.getDefaultMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(
            ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse("请求参数不合法");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(400, message));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Result<Void>> handleUserNotFound(
            UserNotFoundException exception) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Result.failure(404, exception.getMessage()));
    }

    @ExceptionHandler(LoginFailedException.class)
    public ResponseEntity<Result<Void>> handleLoginFailed(
            LoginFailedException exception) {

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Result.failure(401, exception.getMessage()));
    }
}
