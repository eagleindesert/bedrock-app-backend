package com.bedrock.app.item.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@SQLRestriction("deleted_at IS NULL")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(columnDefinition = "text")
    private String name;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "collection_id")
    private UUID collectionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "block_codes", columnDefinition = "jsonb")
    private Set<String> blockCodes = new LinkedHashSet<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new HashMap<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @Builder
    public Item(
            String name,
            Long ownerId,
            UUID collectionId,
            Set<String> blockCodes,
            Map<String, Object> attributes
    ) {
        this.name = name;
        this.ownerId = ownerId;
        this.collectionId = collectionId;
        this.blockCodes = blockCodes == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(blockCodes);
        this.attributes = attributes == null
                ? new HashMap<>()
                : new HashMap<>(attributes);
    }

    public void update(String name, Map<String, Object> attributes) {
        this.name = name;
        this.attributes = attributes != null ? attributes : new HashMap<>();
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }
}