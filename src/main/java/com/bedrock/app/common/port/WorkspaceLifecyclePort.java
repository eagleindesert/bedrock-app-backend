package com.bedrock.app.common.port;

import java.util.UUID;

/**
 * 명세 F-01 · F-06 · F-15 · F-40:
 * 가입할 때 기본 컬렉션과 기본 설정을 만들고,
 * 탈퇴 즉시 또는 30일 유예 만료 때 그 계정의 데이터를 물리 삭제한다.
 */
public interface WorkspaceLifecyclePort {
    UUID initializeWorkspaceForNewUser(UUID userId);
    void purgeAllWorkspaceDataForUser(UUID userId);
}
