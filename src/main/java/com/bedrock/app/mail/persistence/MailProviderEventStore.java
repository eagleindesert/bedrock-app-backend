package com.bedrock.app.mail.persistence;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository("mailProviderEventStore")
public class MailProviderEventStore {
    private final JdbcTemplate jdbc;
    public MailProviderEventStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean receive(String idHmac, String payloadHmac, Instant receivedAt) {
        int inserted = jdbc.update("INSERT INTO mail_provider_events (provider, event_hmac, payload_hmac, received_at) VALUES ('SENDGRID', ?, ?, ?) ON CONFLICT DO NOTHING",
                idHmac, payloadHmac, Timestamp.from(receivedAt));
        if (inserted == 1) return true;
        String prior = jdbc.queryForObject("SELECT payload_hmac FROM mail_provider_events WHERE provider = 'SENDGRID' AND event_hmac = ?", String.class, idHmac);
        if (!payloadHmac.equals(prior)) throw new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION);
        return false;
    }

    public void suppress(String email, Instant now) {
        jdbc.update("INSERT INTO mail_bounces (email, bounced, updated_at) VALUES (?, TRUE, ?) ON CONFLICT (email) DO UPDATE SET bounced = TRUE, updated_at = EXCLUDED.updated_at",
                email, Timestamp.from(now));
    }

    public void deleteReceivedBefore(Instant before) {
        jdbc.update("DELETE FROM mail_provider_events WHERE received_at < ?", Timestamp.from(before));
    }
}
