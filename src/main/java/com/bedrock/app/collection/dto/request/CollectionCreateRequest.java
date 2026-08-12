package com.bedrock.app.collection.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.Map;

@Schema(description = "컬렉션 생성 요청")
@Getter
public class CollectionCreateRequest {

    @Schema(description = "컬렉션 종류 (대소문자 구분 없음)", example = "calendar",
            allowableValues = {"calendar", "notebook", "semester"})
    private String kind;

    @Schema(description = "컬렉션 이름", example = "2026-2학기 시간표")
    private String name;

    @Schema(description = "컬렉션 테마 색상", example = "#FF6B6B")
    private String color;

    @Schema(description = "컬렉션 아이콘 식별자", example = "calendar-icon")
    private String icon;

    @Schema(description = "임의의 속성 객체 (JSONB)", example = "{\"semester\": \"2026-2\"}")
    private Map<String, Object> attributes;
}
