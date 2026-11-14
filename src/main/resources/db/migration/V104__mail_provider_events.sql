-- 제공자 이벤트의 원문·주소·사유 대신 HMAC만 둔다. 반송 주소는 기존 mail_bounces가 관리한다.
CREATE TABLE mail_provider_events (
    provider VARCHAR(30) NOT NULL,
    event_hmac VARCHAR(64) NOT NULL,
    payload_hmac VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (provider, event_hmac)
);
CREATE INDEX idx_mail_provider_events_received ON mail_provider_events(received_at);
