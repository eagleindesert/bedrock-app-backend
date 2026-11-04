package com.bedrock.app.crypto.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/** 공통 명세의 user_data_keys 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "user_data_keys")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class UserDataKey {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "wrapped_dek", nullable = false, columnDefinition = "bytea")
    private byte[] wrappedDek;

    @Column(name = "kms_key_version", nullable = false, length = 64)
    private String kmsKeyVersion;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "rotated_at", nullable = false)
    @Builder.Default
    private Instant rotatedAt = Instant.now();
}
