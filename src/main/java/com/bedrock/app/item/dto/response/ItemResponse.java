package com.bedrock.app.item.dto.response;

import com.bedrock.app.item.domain.Item;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Schema(description = "아이템 응답")
public record ItemResponse(
        @Schema(description = "아이템 고유 식별자", example = "3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f")
        UUID id,

        @Schema(description = "아이템 이름", example = "노트북")
        String name,

        @Schema(description = "소유자 사용자 ID", example = "1")
        Long ownerId,

        @Schema(description = "소속 컬렉션 ID", example = "835c78c3-8d10-4f26-a40b-9c8cf0599886")
        UUID collectionId,

        @Schema(description = "아이템을 구성한 Block 코드 목록", example = "[\"TODO\"]")
        Set<String> blockCodes,

        @Schema(description = "사용자 정의 속성 객체 (JSONB)",
                example = "{\"brand\": \"Dell\", \"ram\": 32, \"tags\": [\"work\", \"portable\"]}")
        Map<String, Object> attributes,

        @Schema(description = "생성 일시", example = "2026-06-24T01:55:00")
        LocalDateTime createdAt,

        @Schema(description = "수정 일시", example = "2026-06-24T01:55:00")
        LocalDateTime updatedAt
) {
    public static ItemResponse from(Item item) {
        return new ItemResponse(
                item.getId(),
                item.getName(),
                item.getOwnerId(),
                item.getCollectionId(),
                item.getBlockCodes() == null ? Set.of() : Set.copyOf(item.getBlockCodes()),
                item.getAttributes(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}