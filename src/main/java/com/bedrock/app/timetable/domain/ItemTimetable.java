package com.bedrock.app.timetable.domain;

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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 공통 명세의 item_timetables 저장 모델. 시간표 아이템의 기간은 아이템마다 하나다(F-30).
 * 시간대 목록은 {@link TimetableSlot}, 소속 컬렉션은 items.collection_id가 갖는다.
 */
@Entity
@Table(name = "item_timetables")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class ItemTimetable {
    @Id
    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 속한 워크스페이스. 동기화 잠금 · 커서의 단위다(V9). */
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    /** 나뉜 시간표 아이템 사슬의 루트 id. 처음 아이템은 자기 id다(F-33, V9). */
    @Column(name = "series_root_id", nullable = false)
    private UUID seriesRootId;

    /** 나뉜 뒤 아이템의 적용 시작일(주의 월요일). 처음 아이템은 null이다. */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
