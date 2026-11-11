-- 공통 기반 보정: 「API 명세 합의 v1.0」(2026-11-10)이 기대는 스키마로 맞춘다.
-- V5(공통-파일.md 2026-11-01 스키마)는 체크섬을 지키려고 고치지 않고, 바뀐 것만 여기서 더한다.
-- 이유와 범위는 공통-파일.md 2.4에 있다.
-- 아직 어떤 기능도 이 테이블들에 쓰지 않으므로, NOT NULL 열은 기본값 없이 더한다.
-- 행이 남아 있는 DB라면 이 마이그레이션이 실패하므로 그 DB를 먼저 확인한다.

-- =====================================================================
-- 0. 동기화 규칙 (모든 담당이 지킨다)
-- =====================================================================
-- 1) 한 사용자의 데이터를 바꾸는 모든 트랜잭션(API · 휴지통 정리 · 탈퇴 정리 · 시스템 안내 등)은
--    맨 앞에서 pg_advisory_xact_lock(hashtext(user_id::text))를 잡는다.
--    같은 사용자의 커밋이 직렬화되어 sync_seq 순서가 커밋 순서와 같아진다.
-- 2) sync_seq 기본값은 INSERT 때만 돈다. UPDATE마다 sync_seq = nextval('workspace_sync_seq')를 직접 넣는다.
-- 3) 행을 지우는 모든 경우(연결 해제 · 고정 해제 · 물리 삭제 · 대화 삭제)는 같은 트랜잭션에서
--    sync_tombstones에 한 줄을 넣는다. CASCADE로 함께 지워지는 행도 마찬가지다.
-- 4) item_recurrences · item_timetables · timetable_slots는 ItemDto의 일부라 바뀌면 그 items.sync_seq를 올린다.

-- =====================================================================
-- 1. 쓰기 기록 · 동기화 (BE 2)
-- =====================================================================

-- 단건 쓰기 멱등 기록. writeId를 쓰는 모든 API(아이템 · 링크 · 컬렉션 · 휴지통 복구 · 충돌 해결 ·
-- 되돌리기 · 설정 · 보기 상태 · 고정 · 대화 기록)가 같은 트랜잭션에서 한 줄을 넣는다.
-- 같은 (user_id, write_id)와 같은 request_hash면 저장한 응답을 alreadyProcessed=true로 돌려주고,
-- 해시가 다르면 409 IDEMPOTENCY_KEY_REUSED로 거절한다.
CREATE TABLE IF NOT EXISTS write_requests (
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    write_id                UUID NOT NULL,
    endpoint                VARCHAR(60) NOT NULL,       -- 예: ITEM_PATCH | LINK_DELETE | UNDO
    request_hash            VARCHAR(64) NOT NULL,       -- 정규화한 요청 본문의 SHA-256
    response_snapshot_enc   TEXT NOT NULL,              -- 재전송 때 돌려줄 암호화 응답
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    purge_after             TIMESTAMPTZ NOT NULL,       -- iOS 미전송 쓰기 보관(30일)보다 길게 둔다
    PRIMARY KEY (user_id, write_id)
);
CREATE INDEX IF NOT EXISTS idx_write_requests_purge ON write_requests(purge_after);

-- 증분 동기화의 삭제 흔적. /workspace/changes가 sync_seq로 다른 변경과 함께 읽는다.
-- 보관은 35일이다(SYNC_CURSOR_EXPIRED 기준과 같다).
CREATE TABLE IF NOT EXISTS sync_tombstones (
    sync_seq        BIGINT PRIMARY KEY DEFAULT nextval('workspace_sync_seq'),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_type   VARCHAR(30) NOT NULL,               -- WorkspaceResourceType(LINK | PIN | ITEM | CONVERSATION 등)
    resource_id     VARCHAR(100) NOT NULL,              -- UUID 또는 복합 키를 문자열로
    deleted_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sync_tombstones_user_sync ON sync_tombstones(user_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_sync_tombstones_deleted ON sync_tombstones(deleted_at);

-- 묶음 쓰기 기록: 사용자별 키, 같은 ID 다른 내용 판정, 같은 AI 요청 1회 저장, 대화 삭제 뒤 되돌리기 막기
ALTER TABLE batch_requests DROP CONSTRAINT batch_requests_pkey;
ALTER TABLE batch_requests ADD PRIMARY KEY (user_id, batch_id);
ALTER TABLE batch_requests ADD COLUMN request_hash VARCHAR(64) NOT NULL;
ALTER TABLE batch_requests ADD COLUMN ai_request_id UUID NULL;
ALTER TABLE batch_requests ADD COLUMN conversation_id UUID NULL;
ALTER TABLE batch_requests ADD COLUMN undo_blocked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE batch_requests ALTER COLUMN response_snapshot_enc DROP NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_batch_requests_ai ON batch_requests(user_id, ai_request_id)
    WHERE ai_request_id IS NOT NULL AND status = 'COMPLETED';
-- status는 COMPLETED | FAILED로 쓴다. 되돌림은 결과 단위로 batch_results에 남긴다.
-- response_snapshot_enc는 아이템을 물리 삭제할 때 비운다(명세 4.4).

-- 묶음 결과 하나하나의 되돌리기 상태
CREATE TABLE IF NOT EXISTS batch_results (
    user_id         UUID NOT NULL,
    batch_id        UUID NOT NULL,
    result_id       UUID NOT NULL,
    operation_id    UUID NOT NULL,
    item_id         UUID NOT NULL,
    undone_at       TIMESTAMPTZ NULL,
    PRIMARY KEY (user_id, batch_id, result_id),
    FOREIGN KEY (user_id, batch_id) REFERENCES batch_requests(user_id, batch_id) ON DELETE CASCADE
);

-- sync_seq가 없던 동기화 자원
ALTER TABLE trash_bundles ADD COLUMN sync_seq BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq');
CREATE INDEX IF NOT EXISTS idx_trash_bundles_user_sync ON trash_bundles(user_id, sync_seq);
ALTER TABLE ics_import_records ADD COLUMN sync_seq BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq');
CREATE INDEX IF NOT EXISTS idx_ics_import_records_user_sync ON ics_import_records(user_id, sync_seq);
ALTER TABLE input_screen_configs ADD COLUMN sync_seq BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq');

-- =====================================================================
-- 2. 칸 버전 · 되돌리기 · 충돌 (BE 2)
-- =====================================================================

-- 칸별 "지금" 버전만 둔다. 직전 값은 쓰기마다 item_field_writes에 남긴다.
-- field_key는 API 칸 키를 쓴다: NAME | BODY | LOCATION | TAGS | COLOR | COLLECTION | START_AT | END_AT |
-- START_DATE | END_DATE | IS_ALL_DAY | TODO_STATUS | DUE_AT | PRIORITY | ESTIMATED_MINUTES | ALARMS |
-- RECURRENCE_RULE | TIMETABLE_SLOTS | EXCEPTION:YYYY-MM-DD | EXCEPTION:YYYY-MM-DD:<slotId>
ALTER TABLE item_field_versions DROP CONSTRAINT item_field_versions_item_id_field_key_key;
ALTER TABLE item_field_versions DROP CONSTRAINT item_field_versions_pkey;
ALTER TABLE item_field_versions DROP COLUMN id;
ALTER TABLE item_field_versions DROP COLUMN previous_value_enc;
ALTER TABLE item_field_versions ALTER COLUMN last_write_id DROP NOT NULL;
ALTER TABLE item_field_versions ADD PRIMARY KEY (item_id, field_key);

-- 칸 쓰기 이력. 쓰기 하나가 바꾼 칸마다 한 줄.
-- 되돌리기는 대상 쓰기가 그 칸의 마지막 "되돌리지 않은" 쓰기일 때만 previous_value_enc로 돌리고,
-- undone_at을 찍은 뒤 item_field_versions.last_write_id를 previous_write_id로 되돌린다.
-- 그러면 앞 요청이 다시 마지막 쓰기가 되어 이어서 되돌릴 수 있다(F-48).
CREATE TABLE IF NOT EXISTS item_field_writes (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id             UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    field_key           VARCHAR(100) NOT NULL,
    write_id            UUID NOT NULL,                  -- 단건 writeId 또는 묶음 안 operationId
    batch_id            UUID NULL,
    result_id           UUID NULL,                      -- 묶음 결과 하나(되돌리기 단위)
    previous_write_id   UUID NULL,                      -- 이 쓰기 직전의 last_write_id
    previous_value_enc  TEXT NULL,                      -- 암호화한 직전 값
    had_previous        BOOLEAN NOT NULL,               -- 회차 예외: 요청 전에 예외 값이 있었는지
    undone_at           TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (item_id, field_key, write_id)
);
CREATE INDEX IF NOT EXISTS idx_item_field_writes_write ON item_field_writes(user_id, write_id);
CREATE INDEX IF NOT EXISTS idx_item_field_writes_result ON item_field_writes(user_id, batch_id, result_id);

-- 충돌 후보: 칸 값 충돌과 짝 없는 회차 예외를 가른다
ALTER TABLE conflict_candidates ADD COLUMN kind VARCHAR(30) NOT NULL;   -- FIELD_VALUE | ORPHANED_OCCURRENCE
ALTER TABLE conflict_candidates ADD COLUMN occurrence_date DATE NULL;
ALTER TABLE conflict_candidates ADD COLUMN occurrence_slot_id UUID NULL;
CREATE INDEX IF NOT EXISTS idx_conflict_candidates_item_field ON conflict_candidates(item_id, field_key);
CREATE INDEX IF NOT EXISTS idx_conflict_candidates_user_sync ON conflict_candidates(user_id, sync_seq);

-- =====================================================================
-- 3. 반복 · 시간표 (BE 3)
-- =====================================================================

-- 회차 예외 키에 시간대를 더한다. 일반 반복은 slot_id가 비고, 시간표 회차는 시간대까지 가린다.
ALTER TABLE item_exceptions ADD COLUMN slot_id UUID NULL;
ALTER TABLE item_exceptions DROP CONSTRAINT item_exceptions_master_item_id_recurrence_date_key;
ALTER TABLE item_exceptions ADD CONSTRAINT uq_item_exceptions_occurrence
    UNIQUE NULLS NOT DISTINCT (master_item_id, recurrence_date, slot_id);

-- 시간표 아이템의 기간은 아이템마다 하나다(F-30). 시간대 행마다 두던 기간을 여기로 모은다.
CREATE TABLE IF NOT EXISTS item_timetables (
    item_id         UUID PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period_start    DATE NOT NULL,                      -- 비우면 만든 날(F-30)
    period_end      DATE NULL,                          -- 비우면 끝 없음(F-30)
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (period_end IS NULL OR period_end >= period_start)
);
INSERT INTO item_timetables (item_id, user_id, period_start, period_end)
SELECT DISTINCT ON (item_id) item_id, user_id, period_start, period_end
FROM timetable_slots
ORDER BY item_id, period_start;

-- 시간대 행에는 시간대만 둔다. 소속은 items.collection_id, 기간은 item_timetables가 갖는다.
-- 겹침 검사는 items.collection_id와 item_timetables 기간을 조인해 사용자 잠금 안에서 한다.
DROP INDEX IF EXISTS idx_timetable_slots_collection;
ALTER TABLE timetable_slots DROP CONSTRAINT timetable_slots_item_id_fkey;
ALTER TABLE timetable_slots ADD CONSTRAINT fk_timetable_slots_item_timetable
    FOREIGN KEY (item_id) REFERENCES item_timetables(item_id) ON DELETE CASCADE;
ALTER TABLE timetable_slots DROP COLUMN collection_id;
ALTER TABLE timetable_slots DROP COLUMN period_start;
ALTER TABLE timetable_slots DROP COLUMN period_end;
CREATE INDEX IF NOT EXISTS idx_timetable_slots_item ON timetable_slots(item_id);
CREATE INDEX IF NOT EXISTS idx_timetable_slots_user_day ON timetable_slots(user_id, day_of_week);
-- timetable_slots.id는 API의 slotId다. 시간표 전체 수정(ALL)에서 유지되는 시간대는 같은 id를 쓴다.
-- item_recurrences.until_date는 반복의 종료일만 뜻한다. 시간표 기간 종료일은 item_timetables.period_end다.

-- =====================================================================
-- 4. AI · 대화 (BE 1)
-- =====================================================================

-- 해석 요청은 되묻기 대기만이 아니라 모든 요청을 한 줄로 둔다.
-- status: READY | AWAITING_ANSWER | COMPLETED | CANCELLED
ALTER TABLE ai_pending_requests ADD COLUMN revision VARCHAR(64) NOT NULL;   -- expectedAiRevision · AI_REQUEST_CHANGED 검사
ALTER TABLE ai_pending_requests ADD COLUMN conversation_id UUID NULL;

ALTER TABLE conversations ADD COLUMN request_id UUID NULL;                  -- 이어 답할 해석 요청
ALTER TABLE conversations ADD COLUMN revision VARCHAR(64) NOT NULL;         -- expectedRevision 검사
ALTER TABLE conversations ADD COLUMN sync_seq BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq');
CREATE INDEX IF NOT EXISTS idx_conversations_user_sync ON conversations(user_id, sync_seq);
