package com.bedrock.app.common.error;

// BE 3만 수정하는 에러 코드 파일
import org.springframework.http.HttpStatus;

public enum Be3ScheduleErrorCode implements ErrorCode {
    DEFAULT_COLLECTION_CANNOT_DELETE(HttpStatus.BAD_REQUEST, "기본 컬렉션은 지울 수 없습니다."),
    TIMETABLE_SLOT_OVERLAP(HttpStatus.CONFLICT, "같은 컬렉션의 시간표 시간대와 겹칩니다."),
    INVALID_RECURRENCE_SPLIT_BOUNDARY(HttpStatus.BAD_REQUEST, "적용 시작일 범위를 나눌 수 없습니다."),
    POSTPONE_UNDO_NOT_ALLOWED(HttpStatus.CONFLICT, "이월본이 미완료 상태일 때만 되돌릴 수 있습니다."),
    INVALID_ICS_FORMAT(HttpStatus.BAD_REQUEST, "유효하지 않은 ICS 파일입니다.");

    private final HttpStatus status;
    private final String message;

    Be3ScheduleErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
