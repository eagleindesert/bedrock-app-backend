package com.bedrock.app.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 공통 명세의 sync_tombstones 저장 모델. 행이 지워진 사실을 증분 동기화로 다른 기기에 알린다.
 * 행을 지우는 트랜잭션 안에서 함께 넣고, 35일 뒤 정리한다(SYNC_CURSOR_EXPIRED 기준).
 */
@Entity
@Table(name = "sync_tombstones")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SyncTombstone {
    @Id
    @SequenceGenerator(name = "workspace_sync_seq", sequenceName = "workspace_sync_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "workspace_sync_seq")
    @Column(name = "sync_seq", nullable = false)
    private Long syncSeq;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 속한 워크스페이스. 동기화 잠금 · 커서의 단위다(V9). */
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** WorkspaceResourceType(LINK · PIN · ITEM · CONVERSATION 등) */
    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    /** UUID 또는 복합 키를 문자열로 */
    @Column(name = "resource_id", nullable = false, length = 100)
    private String resourceId;

    @Column(name = "deleted_at", nullable = false)
    @Builder.Default
    private Instant deletedAt = Instant.now();
}
