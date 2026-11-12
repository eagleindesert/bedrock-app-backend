package com.bedrock.app.common.port;

import java.util.UUID;

/**
 * 명세 F-01 · F-06 · F-15 · F-40 · F-46:
 * 가입할 때 워크스페이스(MVP는 계정마다 하나) · 기본 컬렉션 · 기본 설정을 만들고,
 * 탈퇴 즉시 또는 30일 유예 만료 때 그 계정의 데이터를 물리 삭제한다.
 */
public interface WorkspaceLifecyclePort {
    /**
     * @param timeZone 가입한 기기의 IANA 시간대(DeviceContextDto.timeZone). 기본 설정의 timeZone 처음 값이 된다.
     *                 IANA 이름이 아니면({@link com.bedrock.app.common.domain.ItemTimeZones#isIanaZoneId}) 요청을 거절한다.
     * @return 만든 워크스페이스 id
     */
    UUID initializeWorkspaceForNewUser(UUID userId, String timeZone);
    void purgeAllWorkspaceDataForUser(UUID userId);
}
