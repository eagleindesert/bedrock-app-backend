package com.bedrock.app.collection.dto.response;

import com.bedrock.app.collection.domain.Collection;
import com.bedrock.app.collection.domain.CollectionRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "컬렉션 응답")
public record CollectionResponse(
        @Schema(description = "컬렉션 고유 식별자", example = "3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f")
        UUID id,

        @Schema(description = "컬렉션 종류 (소문자)", example = "calendar")
        String kind,

        @Schema(description = "컬렉션 이름", example = "2026-2학기 시간표")
        String name,

        @Schema(description = "컬렉션 색상", example = "#FF6B6B")
        String color,

        @Schema(description = "컬렉션 아이콘", example = "calendar-icon")
        String icon,

        @Schema(description = "사용자 정의 속성 객체 (JSONB)", example = "{\"semester\": \"2026-2\"}")
        Map<String, Object> attributes,

        @Schema(description = "현재 사용자의 역할", example = "owner")
        String role,

        @Schema(description = "생성 일시", example = "2026-07-10T01:55:00")
        LocalDateTime createdAt,

        @Schema(description = "수정 일시", example = "2026-07-10T01:55:00")
        LocalDateTime updatedAt
) {
    public static CollectionResponse from(Collection collection, CollectionRole role) {
        return new CollectionResponse(
                collection.getId(),
                collection.getKind().name().toLowerCase(),
                collection.getName(),
                collection.getColor(),
                collection.getIcon(),
                collection.getAttributes(),
                role != null ? role.name().toLowerCase() : null,
                collection.getCreatedAt(),
                collection.getUpdatedAt()
        );
    }
}
