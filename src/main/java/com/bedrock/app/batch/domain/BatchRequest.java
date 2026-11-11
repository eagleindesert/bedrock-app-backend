package com.bedrock.app.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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

/**
 * 공통 명세의 batch_requests 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다.
 * 키는 (user_id, batch_id)라서 다른 사용자가 같은 batchId를 보내도 부딪치지 않는다.
 */
@Entity
@Table(name = "batch_requests")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class BatchRequest {
    @EmbeddedId
    private BatchRequestId id;

    @Column(name = "batch_type", nullable = false, length = 30)
    private String batchType;

    /** COMPLETED | FAILED. 되돌림은 결과 단위로 batch_results에 남긴다. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 정규화한 요청 본문의 SHA-256. 같은 batchId에 다른 본문이면 IDEMPOTENCY_KEY_REUSED. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** 같은 AI 요청은 묶음 ID가 달라도 한 번만 완료된다(부분 UNIQUE 인덱스). */
    @Column(name = "ai_request_id")
    private UUID aiRequestId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    /** 연결된 대화 기록이 지워지면 true. 이후 이 묶음의 결과는 되돌리지 않는다. */
    @Column(name = "undo_blocked", nullable = false)
    private boolean undoBlocked;

    /** 재전송 때 돌려줄 암호화 응답. 아이템을 물리 삭제할 때 비운다(명세 4.4). */
    @Column(name = "response_snapshot_enc", columnDefinition = "text")
    private String responseSnapshotEnc;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
