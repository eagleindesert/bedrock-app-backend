package com.bedrock.app.collection.domain;

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
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "collections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@SQLRestriction("deleted_at IS NULL")
public class Collection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CollectionKind kind;

    @Column(columnDefinition = "text")
    private String name;

    private String color;

    private String icon;

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
    public Collection(CollectionKind kind, String name, String color, String icon, Map<String, Object> attributes) {
        this.kind = kind;
        this.name = name;
        this.color = color;
        this.icon = icon;
        this.attributes = attributes != null ? attributes : new HashMap<>();
    }

    /**
     * PATCH 부분 수정 — null인 필드는 건드리지 않고, attributes는 통째 교체가 아니라 병합한다.
     * (kind=semester인 컬렉션의 attributes.start_date/end_date가 바뀌는 경우 인스턴스 재생성이
     * 필요할 수 있으나, 이는 후속 작업으로 별도 처리한다.)
     */
    public void applyPatch(String name, String color, String icon, Map<String, Object> attributesPatch) {
        if (name != null) {
            this.name = name;
        }
        if (color != null) {
            this.color = color;
        }
        if (icon != null) {
            this.icon = icon;
        }
        if (attributesPatch != null) {
            this.attributes.putAll(attributesPatch);
        }
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }
}
