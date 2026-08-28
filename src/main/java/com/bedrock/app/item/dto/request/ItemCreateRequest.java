package com.bedrock.app.item.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.Map;

@Schema(description = "아이템 생성 요청")
@Getter
public class ItemCreateRequest {

    @Schema(description = "아이템 이름", example = "노트북")
    private String name;

    @Schema(description = "임의의 속성 객체 (JSONB)",
            example = "{\"brand\": \"Dell\", \"ram\": 32, \"tags\": [\"work\", \"portable\"]}")
    private Map<String, Object> attributes;
}
