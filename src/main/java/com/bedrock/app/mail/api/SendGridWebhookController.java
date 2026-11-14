package com.bedrock.app.mail.api;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.security.SendGridWebhookVerifier;
import com.bedrock.app.mail.service.SendGridWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

@RestController("sendGridWebhookController")
@Tag(name = "메일 제공자 알림", description = "설정한 공개키로 인증한 서버 간 이벤트만 수신한다.")
public class SendGridWebhookController {
    public static final String PATH = "/api/v1/mail/events/sendgrid";
    private final SendGridWebhookService service;
    public SendGridWebhookController(SendGridWebhookService service) { this.service = service; }

    @PostMapping(PATH)
    @Operation(summary = "SendGrid 전달·영구 반송 수신", description = "원본 바이트의 ECDSA 서명을 확인한다. 이벤트 재전송을 중복 처리하지 않으며 설정 전에는 503으로 실패한다.")
    public ResponseEntity<Void> receive(HttpServletRequest request) {
        String timestamp = header(request, SendGridWebhookVerifier.TIMESTAMP_HEADER);
        String signature = header(request, SendGridWebhookVerifier.SIGNATURE_HEADER);
        byte[] body = null;
        try {
            if (request.getContentLengthLong() > SendGridWebhookVerifier.MAX_BODY_BYTES) throw invalid();
            body = request.getInputStream().readNBytes(SendGridWebhookVerifier.MAX_BODY_BYTES + 1);
            if (body.length > SendGridWebhookVerifier.MAX_BODY_BYTES) throw invalid();
            service.receive(body, timestamp, signature);
            return ResponseEntity.noContent().build();
        } catch (IOException exception) {
            throw invalid();
        } finally {
            if (body != null) Arrays.fill(body, (byte) 0);
        }
    }

    private String header(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) throw new BusinessException(Be1AuthAiErrorCode.INVALID_MAIL_WEBHOOK_SIGNATURE);
        return values.getFirst();
    }
    private BusinessException invalid() { return new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION); }
}
