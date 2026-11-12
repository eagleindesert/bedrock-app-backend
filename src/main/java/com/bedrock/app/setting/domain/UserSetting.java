package com.bedrock.app.setting.domain;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 공통 명세의 user_settings 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "user_settings")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class UserSetting {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "theme", nullable = false, length = 20)
    @Builder.Default
    private String theme = "LIGHT";

    @Column(name = "week_start_day", nullable = false, length = 10)
    @Builder.Default
    private String weekStartDay = "MONDAY";

    @Column(name = "day_start_hour", nullable = false)
    @Builder.Default
    private Short dayStartHour = (short) 7;

    /** 계정의 기본 IANA 시간대. 새 아이템이 받는 값이고, 처음 값은 가입한 기기의 시간대다. 기본값을 두지 않는다. */
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "density_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> densityJson = Map.of("month", "COMPACT", "default", "STANDARD");

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shortcuts_json", columnDefinition = "jsonb")
    private Map<String, Object> shortcutsJson;

    @Column(name = "ai_guideline_enc", columnDefinition = "text")
    private String aiGuidelineEnc;

    @Column(name = "ai_consent", nullable = false)
    private boolean aiConsent;

    @Column(name = "ocr_server_consent", nullable = false)
    private boolean ocrServerConsent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_collection_ids_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<UUID> visibleCollectionIdsJson = List.of();

    @Generated(event = EventType.INSERT)
    @Column(name = "sync_seq", nullable = false, insertable = false)
    private Long syncSeq;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
