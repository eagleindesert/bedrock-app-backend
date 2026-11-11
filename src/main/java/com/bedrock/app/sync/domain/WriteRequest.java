package com.bedrock.app.sync.domain;

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

/**
 * 공통 명세의 write_requests 저장 모델. writeId를 쓰는 단건 쓰기의 멱등 기록이다.
 * 같은 (userId, writeId)와 같은 요청 해시면 저장한 응답을 alreadyProcessed=true로 돌려주고,
 * 해시가 다르면 IDEMPOTENCY_KEY_REUSED로 거절한다. 쓰기와 같은 트랜잭션에서 넣는다.
 */
@Entity
@Table(name = "write_requests")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class WriteRequest {
    @EmbeddedId
    private WriteRequestId id;

    /** 예: ITEM_PATCH | LINK_DELETE | UNDO */
    @Column(name = "endpoint", nullable = false, length = 60)
    private String endpoint;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_snapshot_enc", nullable = false, columnDefinition = "text")
    private String responseSnapshotEnc;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** iOS 미전송 쓰기 보관(30일)보다 길게 둔다. */
    @Column(name = "purge_after", nullable = false)
    private Instant purgeAfter;
}
