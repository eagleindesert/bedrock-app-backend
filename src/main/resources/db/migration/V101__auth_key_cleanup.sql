-- DB 계정 생성 전 외부 키를 만들 때의 보상 작업. 프로세스 종료에도 정리 대상이 남는다.
-- 사용자 글·이메일·키 원문을 보관하지 않는다. 기존 공통 마이그레이션은 변경하지 않는다.
CREATE TABLE auth_key_cleanup (
    user_id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT date_trunc('second', now())
);

CREATE INDEX idx_auth_key_cleanup_created_at ON auth_key_cleanup(created_at);
