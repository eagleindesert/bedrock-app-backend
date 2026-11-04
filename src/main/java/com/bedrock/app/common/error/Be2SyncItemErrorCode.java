package com.bedrock.app.common.error;

// BE 2만 수정하는 에러 코드 파일
import org.springframework.http.HttpStatus;

public enum Be2SyncItemErrorCode implements ErrorCode {
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."), // 없는 대상과 권한 없는 대상은 같은 응답(4.3)
    ACCOUNT_MISMATCH(HttpStatus.FORBIDDEN, "탭의 계정과 세션 계정이 일치하지 않습니다."),
    WRITE_REJECTED_VALIDATION(HttpStatus.BAD_REQUEST, "입력 값이 유효하지 않습니다."),
    WRITE_REJECTED_PURGED(HttpStatus.GONE, "영구 삭제된 대상입니다."),
    UNDO_BLOCKED_BY_LATER_CHANGE(HttpStatus.CONFLICT, "이후 변경이 남아 있어 되돌릴 수 없습니다."),
    BATCH_ATOMIC_WRITE_FAILED(HttpStatus.BAD_REQUEST, "묶음 저장에 실패하여 전체를 되돌렸습니다.");

    private final HttpStatus status;
    private final String message;

    Be2SyncItemErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
