package com.bedrock.app.collection.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * CollectionUser의 복합 기본키 (collection_id, user_id).
 * ADR-024: PRIMARY KEY (collection_id, user_id) — 한 사용자는 한 컬렉션에 하나의 role만 가진다.
 */
public class CollectionUserId implements Serializable {

    private UUID collectionId;
    private Long userId;

    public CollectionUserId() {
    }

    public CollectionUserId(UUID collectionId, Long userId) {
        this.collectionId = collectionId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CollectionUserId that)) return false;
        return Objects.equals(collectionId, that.collectionId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(collectionId, userId);
    }
}
