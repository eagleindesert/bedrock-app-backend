package com.bedrock.app.common.exception;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 에러 응답 바디 스키마.
 *
 * <p>{@link GlobalExceptionHandler} 는 동일한 형태의 {@code Map<String, String>} 을 반환한다.
 * 이 레코드는 OpenAPI 문서에서 에러 바디 형태를 보여주기 위한 용도로만 사용한다.
 */
@Schema(description = "에러 응답")
public record ErrorResponse(
        @Schema(description = "에러 메시지", example = "이미 존재하는 이메일입니다.")
        String message
) {
}
