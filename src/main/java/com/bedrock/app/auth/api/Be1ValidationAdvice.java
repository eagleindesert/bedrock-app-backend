package com.bedrock.app.auth.api;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = {"com.bedrock.app.auth.api", "com.bedrock.app.ai.api", "com.bedrock.app.conversation.api", "com.bedrock.app.ocr.api"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Be1ValidationAdvice {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception ignored) {
        var code = Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION;
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code.name(), code.message(), null));
    }
}
