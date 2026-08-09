package com.bedrock.app.item.dto.response;

import com.bedrock.app.item.domain.Item;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ItemResponse(
        UUID id,
        String name,
        Long ownerId,
        UUID collectionId,
        Set<String> blockCodes,
        Map<String, Object> attributes,
        LocalDateTime createdAt,
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