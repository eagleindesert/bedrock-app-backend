package com.bedrock.app.sync.domain;

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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * 공통 명세의 workspaces 저장 모델(V9). 아이템 · 컬렉션과 그에 딸린 데이터의 단위이고,
 * 동기화 잠금(pg_advisory_xact_lock(hashtext(workspace_id::text)))과 커서도 이 단위다.
 * 한 계정이 여럿 가질 수 있게 두었지만 MVP는 계정마다 하나이고 가입할 때 만든다.
 */
@Entity
@Table(name = "workspaces")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class Workspace {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
