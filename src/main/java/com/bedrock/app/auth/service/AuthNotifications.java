package com.bedrock.app.auth.service;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import com.bedrock.app.mail.service.MailDeliveryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.time.Clock;
import java.util.UUID;

@Service("authNotifications")
public class AuthNotifications {
    private final AuthStore store;
    private final NotMeService notMe;
    private final MailDeliveryService mail;
    private final Clock clock;
    private final String publicOrigin;

    public AuthNotifications(AuthStore store, NotMeService notMe, MailDeliveryService mail,
            @Qualifier("be1Clock") Clock clock, @Value("${bedrock.auth.public-origin:}") String publicOrigin) {
        this.store = store;
        this.notMe = notMe;
        this.mail = mail;
        this.clock = clock;
        this.publicOrigin = publicOrigin;
    }

    public void newDevice(UUID userId, AuthDtos.Platform platform) { send(userId, "새 기기 로그인", platform, ""); }
    public void passwordReset(UUID userId, AuthDtos.Platform platform) { send(userId, "비밀번호 변경", platform, "모든 기기의 로그인을 종료했습니다."); }

    public void newProvider(UUID userId, UUID oauthId, AuthDtos.Provider provider, AuthDtos.Platform platform) {
        URI origin;
        try { origin = URI.create(publicOrigin); }
        catch (IllegalArgumentException exception) { throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY); }
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null || !origin.getPath().isEmpty()) {
            throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
        }
        String token = notMe.issue(userId, oauthId);
        send(userId, provider.name() + " 로그인 연결", platform,
                "직접 연결하지 않았다면 다음 화면에서 연결을 해제해 주세요. 링크는 7일 동안 한 번 사용할 수 있습니다.\n"
                        + origin + "/api/v1/auth/not-me#" + token);
    }

    private void send(UUID userId, String action, AuthDtos.Platform platform, String extra) {
        var account = store.user(userId).orElseThrow();
        String text = "계정 작업: " + action + "\n요청 시각: " + clock.instant() + "\n플랫폼: " + platform + "\n\n" + extra;
        Runnable delivery = () -> mail.submitNotification(account.email(), "[베드락] " + action, text);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { delivery.run(); }
            });
        } else delivery.run();
    }
}
