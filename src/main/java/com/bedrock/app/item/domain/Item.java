package com.bedrock.app.item.domain;

import com.bedrock.app.common.domain.SwatchColor;
import com.bedrock.app.common.domain.TodoStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 공통 명세의 items 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다.
 * createdAt은 기기가 만든 시각이고(날짜 없는 아이템의 만든 날, F-26), serverCreatedAt은 서버 감사 시각이다(V9).
 */
@Entity
@Table(name = "items")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class Item {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 속한 워크스페이스. 동기화 잠금 · 커서의 단위다(V9). */
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    @Column(name = "name_enc", columnDefinition = "text")
    private String nameEnc;

    @Column(name = "tags_enc", columnDefinition = "text")
    private String tagsEnc;

    @Enumerated(EnumType.STRING)
    @Column(name = "color", length = 20)
    private SwatchColor color;

    @Column(name = "has_schedule", nullable = false)
    private boolean hasSchedule;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "is_all_day", nullable = false)
    private boolean isAllDay;

    /** IANA 시간대. 시각 값 · 반복 회차 날짜 · 날짜 없는 아이템의 만든 날을 이 시간대로 정한다. null이면 떠 있는 시각. */
    @Column(name = "time_zone", length = 64)
    private String timeZone;

    @Column(name = "location_enc", columnDefinition = "text")
    private String locationEnc;

    @Column(name = "has_todo", nullable = false)
    private boolean hasTodo;

    @Enumerated(EnumType.STRING)
    @Column(name = "todo_status", length = 20)
    private TodoStatus todoStatus;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** 시각을 고른 마감. dueDate와 함께 있을 수 없다. */
    @Column(name = "due_at")
    private Instant dueAt;

    /** 날짜만 고른 마감. 모든 시간대에서 같은 날에 선다. dueAt과 함께 있을 수 없다. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "priority")
    private Short priority;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "alarms_json", columnDefinition = "jsonb")
    private List<Integer> alarmsJson;

    @Column(name = "has_note", nullable = false)
    private boolean hasNote;

    @Column(name = "body_enc", columnDefinition = "text")
    private String bodyEnc;

    @Column(name = "has_recurrence", nullable = false)
    private boolean hasRecurrence;

    @Column(name = "has_timetable", nullable = false)
    private boolean hasTimetable;

    @Column(name = "is_pending_inbox", nullable = false)
    private boolean isPendingInbox;

    @Column(name = "reference_time")
    private Instant referenceTime;

    @Column(name = "reference_timezone", length = 64)
    private String referenceTimezone;

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

    /** 기기가 만든 시각(ItemCreateSpec.createdAt). 비었거나 서버 시각보다 미래면 서버 시각이다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 서버가 행을 넣은 시각. 감사용이다. */
    @CreatedDate
    @Column(name = "server_created_at", nullable = false, updatable = false)
    private Instant serverCreatedAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 감사 리스너가 serverCreatedAt을 채운 뒤 불린다(JPA는 리스너를 엔티티 콜백보다 먼저 부른다). */
    @PrePersist
    void settleCreatedAt() {
        createdAt = deviceCreatedAt(createdAt, serverCreatedAt);
    }

    /** 기기 시각을 받되 서버 시각보다 미래면 서버 시각으로 자른다. 없으면 서버 시각이다(결정 3). */
    public static Instant deviceCreatedAt(Instant deviceTime, Instant serverTime) {
        if (deviceTime == null || deviceTime.isAfter(serverTime)) return serverTime;
        return deviceTime;
    }
}
