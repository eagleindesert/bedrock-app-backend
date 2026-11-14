-- 삭제된 대화 ID와 쓰기 ID는 사용자 계정 삭제까지 보존한다. 사용자 글은 보존하지 않는다.
CREATE TABLE conversation_deleted_ids (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT date_trunc('second', now()),
    PRIMARY KEY (user_id, conversation_id)
);

CREATE TABLE conversation_writes (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    write_id UUID NOT NULL,
    conversation_id UUID NULL,
    operation VARCHAR(20) NOT NULL,
    response_enc TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT date_trunc('second', now()),
    PRIMARY KEY (user_id, write_id)
);
CREATE INDEX idx_conversation_writes_conversation ON conversation_writes(user_id, conversation_id);
