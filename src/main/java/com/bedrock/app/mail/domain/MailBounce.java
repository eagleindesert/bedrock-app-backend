package com.bedrock.app.mail.domain;

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

/** 공통 명세의 mail_bounces 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "mail_bounces")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class MailBounce {
    @Id
    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "bounced", nullable = false)
    private boolean bounced;

    @Column(name = "consecutive_fail_count", nullable = false)
    @Builder.Default
    private Integer consecutiveFailCount = 0;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
