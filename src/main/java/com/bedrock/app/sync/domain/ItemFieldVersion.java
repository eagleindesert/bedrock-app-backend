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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * 공통 명세의 item_field_versions 저장 모델. 칸마다 지금의 마지막 쓰기만 둔다.
 * 쓰기마다의 직전 값과 되돌리기 상태는 {@link ItemFieldWrite}가 갖는다.
 */
@Entity
@Table(name = "item_field_versions")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class ItemFieldVersion {
    @EmbeddedId
    private ItemFieldVersionId id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * 마지막으로 반영한 쓰기. 처음 값이면 null(API의 baseFieldVersions null과 같다).
     * 단건 쓰기는 그 writeId, 묶음 안의 패치 · 지우기 · 미루기는 그 연산의 operationId를 남긴다. batchId는 남기지 않는다.
     * 기기는 오프라인에서 같은 칸을 이어 고칠 때, 앞 쓰기의 writeId · operationId를 뒤 쓰기의 기준(baseFieldVersions)으로 싣는다.
     * 값은 FieldVersionDto.lastWriteId로 내려가고, item_field_writes.write_id와 같은 값이다.
     */
    @Column(name = "last_write_id")
    private UUID lastWriteId;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
