package com.bedrock.app.mail.security;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;

/**
 * SendGrid가 서명한 timestamp + 원래 HTTP 바이트를 검증한다. 본문을 JSON으로 바꾼 뒤 검증하지 않는다.
 * 프로토콜: https://www.twilio.com/docs/sendgrid/for-developers/tracking-events/getting-started-event-webhook-security-features
 */
@Component("sendGridWebhookVerifier")
public final class SendGridWebhookVerifier {
    public static final String SIGNATURE_HEADER = "X-Twilio-Email-Event-Webhook-Signature";
    public static final String TIMESTAMP_HEADER = "X-Twilio-Email-Event-Webhook-Timestamp";
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final long MAX_AGE_SECONDS = 86400;
    private final ECPublicKey key;
    private final Clock clock;

    public SendGridWebhookVerifier(@Value("${bedrock.mail.sendgrid.webhook.verification-key:}") String verificationKey,
            @Qualifier("be1Clock") Clock clock) {
        this.clock = clock;
        if (verificationKey.isBlank()) {
            key = null;
            return;
        }
        try {
            if (verificationKey.length() > 1024) throw new IllegalArgumentException();
            key = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(verificationKey)));
            if (key.getParams().getCurve().getField().getFieldSize() != 256) throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new IllegalArgumentException("SendGrid webhook 검증 공개키 설정을 확인해 주세요.");
        }
    }

    public void verify(byte[] body, String timestamp, String signature) {
        if (key == null) throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
        if (body == null || body.length > MAX_BODY_BYTES || timestamp == null
                || !timestamp.matches("[0-9]{1,12}") || signature == null || signature.length() > 256) throw invalid();
        try {
            long signedAt = Long.parseLong(timestamp);
            long now = clock.instant().getEpochSecond();
            if (signedAt < now - MAX_AGE_SECONDS || signedAt > now + 300) throw invalid();
            var verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            verifier.update(body);
            if (!verifier.verify(Base64.getDecoder().decode(signature))) throw invalid();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private BusinessException invalid() { return new BusinessException(Be1AuthAiErrorCode.INVALID_MAIL_WEBHOOK_SIGNATURE); }
}
