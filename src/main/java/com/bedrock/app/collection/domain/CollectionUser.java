package com.bedrock.app.collection.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * collection_users — 컬렉션과 사용자의 소속/권한 관계.
 * ADR-024: PRIMARY KEY (collection_id, user_id), role/system_purpose 포함.
 * system_purpose가 not null인 레코드는 (user_id, system_purpose) 기준으로 유일해야 한다
 * (partial unique index — DB 마이그레이션에서 별도 생성 필요, ddl-auto=update로는 생성되지 않음).
 */
@Entity
@Table(name = "collection_users")
@IdClass(CollectionUserId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class CollectionUser {

    @Id
    @Column(name = "collection_id")
    private UUID collectionId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CollectionRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_purpose")
    private CollectionSystemPurpose systemPurpose;

    @CreatedDate
    @Column(name = "joined_at", updatable = false)
    private LocalDateTime joinedAt;

    @Builder
    public CollectionUser(UUID collectionId, Long userId, CollectionRole role, CollectionSystemPurpose systemPurpose) {
        this.collectionId = collectionId;
        this.userId = userId;
        this.role = role != null ? role : CollectionRole.OWNER;
        this.systemPurpose = systemPurpose;
    }

    public void changeRole(CollectionRole role) {
        this.role = role;
    }
}
