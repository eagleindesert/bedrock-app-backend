package com.bedrock.app.conflict.domain;

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
import org.hibernate.generator.EventType;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 공통 명세의 conflict_candidates 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "conflict_candidates")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class ConflictCandidate {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "field_key", nullable = false, length = 100)
    private String fieldKey;

    /** FIELD_VALUE | ORPHANED_OCCURRENCE */
    @Column(name = "kind", nullable = false, length = 30)
    private String kind;

    @Column(name = "occurrence_date")
    private LocalDate occurrenceDate;

    @Column(name = "occurrence_slot_id")
    private UUID occurrenceSlotId;

    @Column(name = "candidate_value_enc", nullable = false, columnDefinition = "text")
    private String candidateValueEnc;

    @Column(name = "source_write_id", nullable = false)
    private UUID sourceWriteId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "sync_seq", nullable = false, insertable = false)
    private Long syncSeq;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
