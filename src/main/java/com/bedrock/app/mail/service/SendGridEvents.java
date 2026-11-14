package com.bedrock.app.mail.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 서명 검증을 마친 이벤트에서 영구 반송·전달 완료만 사용한다. 사유·IP·원문 메타데이터는 보관하지 않는다. */
@Component("sendGridEvents")
public final class SendGridEvents {
    private final ObjectMapper mapper;

    public SendGridEvents(ObjectMapper mapper) { this.mapper = mapper; }

    public List<Event> read(byte[] body) {
        try {
            JsonNode events = mapper.readTree(body);
            if (events == null || !events.isArray() || events.size() > 1000) throw invalid();
            var accepted = new ArrayList<Event>();
            for (JsonNode event : events) {
                if (!event.isObject() || !event.path("event").isString()) throw invalid();
                Outcome outcome;
                if ("delivered".equals(event.path("event").asText())) outcome = Outcome.DELIVERED;
                else if ("bounce".equals(event.path("event").asText()) && "bounce".equals(event.path("type").asText())) outcome = Outcome.BOUNCED;
                else continue; // blocked/deferred는 일시 거부다. 영구 차단 주소로 바꾸지 않는다.
                String id = text(event, "sg_event_id", 512);
                String email = text(event, "email", 320).strip().toLowerCase(Locale.ROOT);
                if (!id.matches("[!-~]+") || email.chars().anyMatch(Character::isWhitespace)) throw invalid();
                var address = new InternetAddress(email, true);
                address.validate();
                if (address.getPersonal() != null || !email.equals(address.getAddress())) throw invalid();
                JsonNode timestamp = event.path("timestamp");
                if (!timestamp.isIntegralNumber() || !timestamp.canConvertToLong() || timestamp.asLong() < 0) throw invalid();
                accepted.add(new Event(id, email, outcome, Instant.ofEpochSecond(timestamp.asLong())));
            }
            return List.copyOf(accepted);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private String text(JsonNode event, String field, int maximum) {
        JsonNode value = event.path(field);
        if (!value.isString() || value.asText().isBlank() || value.asText().length() > maximum) throw invalid();
        return value.asText();
    }

    private BusinessException invalid() { return new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION); }
    public enum Outcome { DELIVERED, BOUNCED }
    public record Event(String id, String email, Outcome outcome, Instant occurredAt) {
        @Override public String toString() { return "MailProviderEvent[outcome=" + outcome + "]"; }
    }
}
