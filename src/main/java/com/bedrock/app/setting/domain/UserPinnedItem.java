package com.bedrock.app.setting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/** 공통 명세의 user_pinned_items 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "user_pinned_items")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserPinnedItem {
    @EmbeddedId
    private UserPinnedItemId id;

    @Column(name = "pinned_at", nullable = false)
    @Builder.Default
    private Instant pinnedAt = Instant.now();

    @Generated(event = EventType.INSERT)
    @Column(name = "sync_seq", nullable = false, insertable = false)
    private Long syncSeq;
}
