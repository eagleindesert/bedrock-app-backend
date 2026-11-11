package com.bedrock.app.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** 공통 명세의 batch_results 저장 모델. 묶음 결과 하나(resultId)의 되돌리기 상태를 둔다. */
@Entity
@Table(name = "batch_results")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BatchResult {
    @EmbeddedId
    private BatchResultId id;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "undone_at")
    private Instant undoneAt;
}
