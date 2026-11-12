package com.bedrock.app.trash.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/** 공통 명세의 trash_bundles 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "trash_bundles")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrashBundle {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 속한 워크스페이스. 동기화 잠금 · 커서의 단위다(V9). */
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "bundle_type", nullable = false, length = 30)
    private String bundleType;

    @Column(name = "root_target_id", nullable = false)
    private UUID rootTargetId;

    @Column(name = "deleted_at", nullable = false)
    @Builder.Default
    private Instant deletedAt = Instant.now();

    @Column(name = "purge_after", nullable = false)
    private Instant purgeAfter;

    @Column(name = "restored_at")
    private Instant restoredAt;

    /** DB가 정한다: INSERT는 기본값, UPDATE는 V10 트리거. JPA는 쓰지 않고 다시 읽는다. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "sync_seq", nullable = false, insertable = false, updatable = false)
    private Long syncSeq;
}
