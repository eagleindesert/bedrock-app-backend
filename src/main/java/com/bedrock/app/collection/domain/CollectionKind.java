package com.bedrock.app.collection.domain;

import java.util.Arrays;

public enum CollectionKind {
    CALENDAR,
    NOTEBOOK,
    SEMESTER;

    public static CollectionKind fromValue(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(kind -> kind.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 kind 입니다: " + value));
    }
}
