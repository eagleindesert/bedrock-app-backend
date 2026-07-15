package com.bedrock.app.collection.dto.response;

import com.bedrock.app.collection.domain.Collection;
import com.bedrock.app.collection.domain.CollectionRole;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record CollectionResponse(
        UUID id,
        String kind,
        String name,
        String color,
        String icon,
        Map<String, Object> attributes,
        String role,
        LocalDateTime createdAt,
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
