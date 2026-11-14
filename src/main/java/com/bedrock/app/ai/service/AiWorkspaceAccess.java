package com.bedrock.app.ai.service;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 공통 읽기 계약이 추가되기 전의 BE1 연동 경계. 동의/소유권을 추측하거나 클라이언트 값으로 대신하지 않는다.
 * 승인된 common.port 어댑터가 준비되면 이 경계가 그 포트에 위임한다.
 */
@Component("aiWorkspaceAccess")
public class AiWorkspaceAccess {
    public void requireAiConsent(UUID userId) { throw unavailable(); }
    public void requireOcrConsent(UUID userId) { throw unavailable(); }
    public void validateContext(UUID userId, AiDtos.InterpretationRequest request) { throw unavailable(); }
    private BusinessException unavailable() { return new BusinessException(CommonErrorCode.FEATURE_NOT_READY); }
}
