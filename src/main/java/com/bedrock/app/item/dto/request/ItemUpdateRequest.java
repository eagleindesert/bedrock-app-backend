package com.bedrock.app.item.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.Map;

@Schema(description = "아이템 수정 요청 (전체 교체)")
@Getter
public class ItemUpdateRequest {

    @Schema(description = "수정할 아이템 이름", example = "노트북 (업무용)")
    private String name;

    @Schema(description = "수정할 속성 객체 (JSONB)",
            example = "{\"brand\": \"Dell\", \"ram\": 64, \"tags\": [\"work\"]}")
    private Map<String, Object> attributes;
}
