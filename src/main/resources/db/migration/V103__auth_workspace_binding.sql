-- MVP는 계정당 워크스페이스 하나다. 가입 포트가 반환한 ID를 BE1 소유 계정에 보관한다.
-- 다른 담당 테이블을 직접 조회하지 않고 V9의 동일한 워크스페이스 잠금을 잡는 데 사용한다.
-- 기존 계정은 신뢰할 수 있는 워크스페이스 ID가 연결되기 전까지 동기화 쓰기를 허용하지 않는다.
ALTER TABLE users ADD COLUMN home_workspace_id UUID NULL;
COMMENT ON COLUMN users.home_workspace_id IS 'WorkspaceLifecyclePort가 반환한 MVP 워크스페이스 ID. BE1 동기화 쓰기 잠금용';
