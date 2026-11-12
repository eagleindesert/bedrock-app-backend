package com.bedrock.app.conversation.domain;

import com.bedrock.app.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.LocalDate;
import java.util.UUID;

/** 공통 명세의 conversations 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "conversations")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Conversation extends BaseTimeEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(name = "content_enc", nullable = false, columnDefinition = "text")
    private String contentEnc;

    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "related_batch_id")
    private UUID relatedBatchId;

    /** expectedRevision 검사용 버전 */
    @Column(name = "revision", nullable = false, length = 64)
    private String revision;

    /** DB가 정한다: INSERT는 기본값, UPDATE는 V10 트리거. JPA는 쓰지 않고 다시 읽는다. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "sync_seq", nullable = false, insertable = false, updatable = false)
    private Long syncSeq;
}
