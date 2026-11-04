package com.bedrock.app.common.error;

import com.bedrock.app.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception) {
        return response(exception.getErrorCode(), exception.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "code",
                        error.getCode() == null ? "Invalid" : error.getCode()))
                .toList();
        return response(CommonErrorCode.INVALID_REQUEST, fields);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequest(Exception exception) {
        return response(CommonErrorCode.INVALID_REQUEST, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> authentication(AuthenticationException exception) {
        return response(Be1AuthAiErrorCode.INVALID_CREDENTIALS, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(AccessDeniedException exception) {
        return response(CommonErrorCode.ACCESS_DENIED, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(DataIntegrityViolationException exception) {
        return response(CommonErrorCode.RESOURCE_CONFLICT, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException exception) {
        return response(Be2SyncItemErrorCode.RESOURCE_NOT_FOUND, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return response(CommonErrorCode.METHOD_NOT_ALLOWED, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedMedia(HttpMediaTypeNotSupportedException exception) {
        return response(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> responseStatus(ResponseStatusException exception) {
        ErrorCode code = switch (exception.getStatusCode().value()) {
            case 401 -> Be1AuthAiErrorCode.UNAUTHORIZED;
            case 403 -> CommonErrorCode.ACCESS_DENIED;
            case 404 -> Be2SyncItemErrorCode.RESOURCE_NOT_FOUND;
            case 409 -> CommonErrorCode.RESOURCE_CONFLICT;
            case 410 -> Be2SyncItemErrorCode.WRITE_REJECTED_PURGED;
            case 503 -> CommonErrorCode.FEATURE_NOT_READY;
            case 500, 502, 504 -> CommonErrorCode.INTERNAL_ERROR;
            default -> CommonErrorCode.INVALID_REQUEST;
        };
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiResponse.fail(code.name(), code.message(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        // 예외 메시지/스택에는 SQL, 바인딩 값, 사용자 본문이 포함될 수 있다.
        LOG.error("Request failed: exceptionType={}", exception.getClass().getName());
        return response(CommonErrorCode.INTERNAL_ERROR, null);
    }

    private ResponseEntity<ApiResponse<Void>> response(ErrorCode code, Object details) {
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code.name(), code.message(), details));
    }
}
