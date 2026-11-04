package com.bedrock.app.item.domain;

import com.bedrock.app.common.domain.BaseTimeEntity;
import com.bedrock.app.common.domain.SwatchColor;
import com.bedrock.app.common.domain.TodoStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 공통 명세의 items 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "items")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Item extends BaseTimeEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

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

    @Column(name = "location_enc", columnDefinition = "text")
    private String locationEnc;

    @Column(name = "has_todo", nullable = false)
    private boolean hasTodo;

    @Enumerated(EnumType.STRING)
    @Column(name = "todo_status", length = 20)
    private TodoStatus todoStatus;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "due_at")
    private Instant dueAt;

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

    @Generated(event = EventType.INSERT)
    @Column(name = "sync_seq", nullable = false, insertable = false)
    private Long syncSeq;
}
