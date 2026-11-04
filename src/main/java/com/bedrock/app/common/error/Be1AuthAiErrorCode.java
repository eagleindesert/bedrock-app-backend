package com.bedrock.app.common.error;

// BE 1만 수정하는 에러 코드 파일
import org.springframework.http.HttpStatus;

public enum Be1AuthAiErrorCode implements ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_MAIL_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않거나 만료되었습니다."),
    MAIL_CODE_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요."),
    AI_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "AI 해석 동의가 필요합니다."),
    OCR_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "캡처 서버 인식 동의가 필요합니다."),
    AI_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AI 호출 한도를 초과했습니다."),
    AI_TIMEOUT_OR_INVALID_SCHEMA(HttpStatus.BAD_GATEWAY, "문장을 해석하지 못했습니다.");

    private final HttpStatus status;
    private final String message;

    Be1AuthAiErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
