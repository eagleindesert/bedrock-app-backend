package com.bedrock.app.common.validation;

import com.bedrock.app.common.domain.ItemTimeZones;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class IanaTimeZoneValidator implements ConstraintValidator<IanaTimeZone, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || ItemTimeZones.isIanaZoneId(value);
    }
}
