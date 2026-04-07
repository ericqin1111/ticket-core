package com.ticket.core.common.handler;

import com.ticket.core.common.dto.ErrorResponse;
import com.ticket.core.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.warn("[{}] Business exception: code={}, message={}", requestId, ex.getErrorCode().getCode(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .code(ex.getErrorCode().getCode())
                .message(ex.getMessage())
                .requestId(requestId)
                .retryable(ex.getErrorCode().isRetryable())
                .build();
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        log.warn("[{}] Validation error: {}", requestId, message);
        ErrorResponse body = ErrorResponse.builder()
                .code("VALIDATION_ERROR")
                .message(message)
                .requestId(requestId)
                .retryable(false)
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        String message = ex.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(error -> {
                    if (error instanceof FieldError fieldError) {
                        return fieldError.getField() + " " + fieldError.getDefaultMessage();
                    }
                    return error.getDefaultMessage();
                })
                .findFirst()
                .orElse("Validation failed");
        log.warn("[{}] Handler method validation error: {}", requestId, message);
        ErrorResponse body = ErrorResponse.builder()
                .code("VALIDATION_ERROR")
                .message(message)
                .requestId(requestId)
                .retryable(false)
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> handleServletRequestBinding(
            ServletRequestBindingException ex, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.warn("[{}] Request binding error: {}", requestId, ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .code("VALIDATION_ERROR")
                .message(ex.getMessage())
                .requestId(requestId)
                .retryable(false)
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.error("[{}] Unexpected error: {}", requestId, ex.getMessage(), ex);
        ErrorResponse body = ErrorResponse.builder()
                .code("INTERNAL_ERROR")
                .message("An unexpected error occurred.")
                .requestId(requestId)
                .retryable(true)
                .build();
        return ResponseEntity.internalServerError().body(body);
    }
}
