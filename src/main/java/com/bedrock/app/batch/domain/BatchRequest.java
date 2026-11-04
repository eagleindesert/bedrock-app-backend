package com.bedrock.app.batch.domain;

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

/** 공통 명세의 batch_requests 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "batch_requests")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class BatchRequest {
    @Id
    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "batch_type", nullable = false, length = 30)
    private String batchType;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "response_snapshot_enc", nullable = false, columnDefinition = "text")
    private String responseSnapshotEnc;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
