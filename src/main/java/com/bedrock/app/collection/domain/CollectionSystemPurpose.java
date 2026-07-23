package com.bedrock.app.collection.domain;

import java.util.Arrays;

public enum CollectionSystemPurpose {
    DEFAULT_CALENDAR,
    TODO_CALENDAR,
    DEFAULT_NOTEBOOK;

    public static CollectionSystemPurpose fromValue(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(purpose -> purpose.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 system_purpose 입니다: " + value));
    }
}
