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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 공통 명세의 item_recurrences 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "item_recurrences")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class ItemRecurrence {
    @Id
    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "rrule", nullable = false, length = 500)
    private String rrule;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rdate_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<LocalDate> rdateJson = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exdate_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<LocalDate> exdateJson = List.of();

    @Column(name = "series_root_id", nullable = false)
    private UUID seriesRootId;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "until_date")
    private LocalDate untilDate;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
