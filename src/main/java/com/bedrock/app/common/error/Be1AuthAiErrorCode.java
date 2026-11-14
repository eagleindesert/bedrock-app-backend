package com.bedrock.app.common.error;

// BE 1만 수정하는 에러 코드 파일
import org.springframework.http.HttpStatus;

public enum Be1AuthAiErrorCode implements ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_MAIL_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않거나 만료되었습니다."),
    INVALID_MAIL_WEBHOOK_SIGNATURE(HttpStatus.UNAUTHORIZED, "메일 제공자 서명을 확인할 수 없습니다."),
    MAIL_CODE_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요."),
    CRYPTO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "암호화 데이터를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    TEMPORARY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    WRITE_REJECTED_VALIDATION(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    RESOURCE_VERSION_CHANGED(HttpStatus.CONFLICT, "작업 상태가 바뀌었습니다. 다시 확인해 주세요."),
    ACCOUNT_MISMATCH(HttpStatus.FORBIDDEN, "탭의 계정과 세션 계정이 일치하지 않습니다."),
    INVALID_NOT_ME_TOKEN(HttpStatus.BAD_REQUEST, "확인 링크가 올바르지 않거나 만료되었습니다."),
    AI_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "AI 해석 동의가 필요합니다."),
    OCR_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "캡처 서버 인식 동의가 필요합니다."),
    AI_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AI 호출 한도를 초과했습니다."),
    AI_REQUEST_CHANGED(HttpStatus.CONFLICT, "다른 기기에서 요청이 바뀌었습니다. 다시 확인해 주세요."),
    OCR_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "캡처 인식 한도를 초과했습니다."),
    OCR_TIMEOUT_OR_INVALID_RESULT(HttpStatus.BAD_GATEWAY, "시간표를 인식하지 못했습니다."),
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
