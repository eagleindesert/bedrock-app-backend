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

    public void update(String name, String color, String icon, Map<String, Object> attributes) {
        this.name = name;
        this.color = color;
        this.icon = icon;
        this.attributes = attributes != null ? attributes : new HashMap<>();
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }
}
