package com.bedrock.app.collection.domain;

import com.bedrock.app.common.domain.BaseTimeEntity;
import com.bedrock.app.common.domain.SwatchColor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** 공통 명세의 collections 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "collections")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Collection extends BaseTimeEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 속한 워크스페이스. 동기화 잠금 · 커서의 단위다(V9). */
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "name_enc", nullable = false, columnDefinition = "text")
    private String nameEnc;

    @Enumerated(EnumType.STRING)
    @Column(name = "color", nullable = false, length = 20)
    private SwatchColor color;

    @Column(name = "icon", nullable = false, length = 50)
    private String icon;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "trash_bundle_id")
    private UUID trashBundleId;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "purge_after")
    private Instant purgeAfter;

    /** DB가 정한다: INSERT는 기본값, UPDATE는 V10 트리거. JPA는 쓰지 않고 다시 읽는다. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "sync_seq", nullable = false, insertable = false, updatable = false)
    private Long syncSeq;
}
