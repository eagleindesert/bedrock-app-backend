package com.bedrock.app.item.dto.response;

import com.bedrock.app.item.domain.Item;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record ItemResponse(
        UUID id,
        String name,
        Long ownerId,
        Map<String, Object> attributes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ItemResponse from(Item item) {
        return new ItemResponse(
                item.getId(),
                item.getName(),
                item.getOwnerId(),
                item.getAttributes(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
