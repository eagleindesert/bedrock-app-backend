package com.bedrock.app.sync.domain;

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

/**
 * 공통 명세의 item_field_writes 저장 모델. 쓰기 하나가 바꾼 칸마다 한 줄을 남긴다.
 * 되돌리기는 대상 쓰기가 그 칸의 마지막 "되돌리지 않은" 쓰기일 때만 직전 값으로 돌리고,
 * item_field_versions.last_write_id를 previousWriteId로 되돌린다(F-48 연속 되돌리기).
 */
@Entity
@Table(name = "item_field_writes")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class ItemFieldWrite {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 속한 워크스페이스. 동기화 잠금 · 커서의 단위다(V9). */
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "field_key", nullable = false, length = 100)
    private String fieldKey;

    /** 단건 writeId 또는 묶음 안 operationId */
    @Column(name = "write_id", nullable = false)
    private UUID writeId;

    @Column(name = "batch_id")
    private UUID batchId;

    /** 묶음 결과 하나(되돌리기 단위) */
    @Column(name = "result_id")
    private UUID resultId;

    @Column(name = "previous_write_id")
    private UUID previousWriteId;

    @Column(name = "previous_value_enc", columnDefinition = "text")
    private String previousValueEnc;

    /** 회차 예외: 요청 전에 예외 값이 있었는지. 없었으면 되돌릴 때 예외 값을 지워 마스터를 따르게 한다. */
    @Column(name = "had_previous", nullable = false)
    private boolean hadPrevious;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
