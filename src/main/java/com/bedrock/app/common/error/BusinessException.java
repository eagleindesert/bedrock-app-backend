package com.bedrock.app.common.error;

import java.util.Objects;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    /** details에는 사용자 글, 토큰, 쿠키 또는 암호화 전 데이터를 넣지 않는다. */
    public BusinessException(ErrorCode errorCode, Object details) {
        super(Objects.requireNonNull(errorCode).message());
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
