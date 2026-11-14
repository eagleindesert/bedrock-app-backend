package com.bedrock.app.auth.service;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.security.MailCodeProtector;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

@Service("notMeService")
public class NotMeService {
    private final AuthStore store;
    private final AuthSessions sessions;
    private final MailCodeProtector protector;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public NotMeService(AuthStore store, AuthSessions sessions, MailCodeProtector protector, @Qualifier("be1Clock") Clock clock) {
        this.store = store;
        this.sessions = sessions;
        this.protector = protector;
        this.clock = clock;
    }

    public String issue(UUID userId, UUID oauthId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.saveNotMe(userId, oauthId, digest(token), clock.instant().plusSeconds(7L * 86400));
        return token;
    }

    public AuthDtos.NotMeInfo inspect(String token) {
        var record = current(token);
        return new AuthDtos.NotMeInfo(record.provider(), record.expiresAt(), true);
    }

    public void revoke(AuthDtos.NotMeRequest input) {
        if (!input.confirmed()) throw invalid();
        var record = current(input.token());
        store.locked(record.userId(), () -> {
            var current = current(input.token());
            if (!store.consumeNotMe(current.id(), clock.instant())) throw invalid();
            sessions.endAll(current.userId(), AuthDtos.SessionEndReason.NOT_ME);
            store.unlinkOAuth(current.userId(), current.oauthId());
            return null;
        });
    }

    private AuthStore.NotMe current(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return store.notMe(digest(token), clock.instant()).orElseThrow(this::invalid);
    }

    private String digest(String token) { return protector.requesterFingerprint("not-me:" + token); }
    private BusinessException invalid() { return new BusinessException(Be1AuthAiErrorCode.INVALID_NOT_ME_TOKEN); }
}
