package com.bedrock.app.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * IANA 시간대 이름(예: Asia/Seoul)만 받는다. null은 통과한다.
 * 아이템의 timeZone(null = 떠 있는 시각), 설정의 timeZone, 가입 기기의 timeZone DTO 칸에 붙인다.
 * 값이 꼭 있어야 하는 칸은 {@code @NotNull}을 함께 붙인다.
 */
@Documented
@Constraint(validatedBy = IanaTimeZoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IanaTimeZone {
    String message() default "IANA 시간대 이름이 아닙니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
