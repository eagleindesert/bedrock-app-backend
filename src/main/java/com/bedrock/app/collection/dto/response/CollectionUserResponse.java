package com.bedrock.app.collection.dto.response;

import com.bedrock.app.collection.domain.CollectionUser;

import java.time.LocalDateTime;

public record CollectionUserResponse(
        Long userId,
        String role,
        String systemPurpose,
        LocalDateTime joinedAt
) {
    public static CollectionUserResponse from(CollectionUser collectionUser) {
        return new CollectionUserResponse(
                collectionUser.getUserId(),
                collectionUser.getRole().name().toLowerCase(),
                collectionUser.getSystemPurpose() != null
                        ? collectionUser.getSystemPurpose().name().toLowerCase()
                        : null,
                collectionUser.getJoinedAt()
        );
    }
}
