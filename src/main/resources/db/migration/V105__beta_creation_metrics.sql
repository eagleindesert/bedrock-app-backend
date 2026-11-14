-- 베타 만들기 지표. 사용자 글·아이템 ID를 보관하지 않고 쓰기별 중복 집계를 막는다.
-- 호출자는 아이템 쓰기와 같은 트랜잭션에서 기록한다. 보관은 최대 35일이다.
CREATE TABLE auth_beta_creations (
    subject_hmac VARCHAR(64) NOT NULL,
    write_hmac VARCHAR(64) NOT NULL,
    created_count INTEGER NOT NULL CHECK (created_count BETWEEN 1 AND 10000),
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (subject_hmac, write_hmac)
);
CREATE INDEX idx_auth_beta_creations_observed ON auth_beta_creations(observed_at);
