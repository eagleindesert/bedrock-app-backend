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
import java.util.Map;
import java.util.UUID;

/** 공통 명세의 user_view_states 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "user_view_states")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class UserViewState {
    /** 캘린더 보기 상태. state_json.hiddenCollectionIds를 갖는다. */
    public static final String VIEW_CALENDAR = "CALENDAR_LAST_VIEW";
    /** 시간표 보기 상태. state_json.hiddenCollectionIds를 갖는다. */
    public static final String VIEW_TIMETABLE = "TIMETABLE";
    /** 그 모듈에서 끈 컬렉션 id 목록. 빈 목록이면 모두 보이고, 새 컬렉션은 보이는 채 생긴다(V9). */
    public static final String HIDDEN_COLLECTION_IDS = "hiddenCollectionIds";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 속한 워크스페이스. 동기화 잠금 · 커서의 단위다(V9). */
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "view_key", nullable = false, length = 50)
    private String viewKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> stateJson;

    /** DB가 정한다: INSERT는 기본값, UPDATE는 V10 트리거. JPA는 쓰지 않고 다시 읽는다. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "sync_seq", nullable = false, insertable = false, updatable = false)
    private Long syncSeq;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
