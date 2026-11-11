package com.bedrock.app.recurrence.domain;

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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 공통 명세의 item_exceptions 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "item_exceptions")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class ItemException {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "master_item_id", nullable = false)
    private UUID masterItemId;

    @Column(name = "recurrence_date", nullable = false)
    private LocalDate recurrenceDate;

    /** 시간표 회차의 시간대(slotId). 일반 반복은 null */
    @Column(name = "slot_id")
    private UUID slotId;

    @Column(name = "is_deleted_occurrence", nullable = false)
    private boolean isDeletedOccurrence;

    @Column(name = "exception_item_id")
    private UUID exceptionItemId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "modified_fields_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> modifiedFieldsJson = List.of();

    @Generated(event = EventType.INSERT)
    @Column(name = "sync_seq", nullable = false, insertable = false)
    private Long syncSeq;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
