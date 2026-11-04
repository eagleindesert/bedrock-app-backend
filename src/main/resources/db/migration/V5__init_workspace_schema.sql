-- 공통-파일.md 2026-11-01 공통 스키마. V1~V3 체크섬을 보존하기 위해 V5로 도입한다.
-- =====================================================================
-- 0. 공통 시퀀스 (F-44 워크스페이스 증분 동기화용 단조 증가 번호)
-- =====================================================================
CREATE SEQUENCE IF NOT EXISTS workspace_sync_seq START WITH 1 INCREMENT BY 1;

-- =====================================================================
-- 1. BE 1 소유 테이블 (계정 · 인증 · 메일 · 암호화 키 · AI · 대화 기록)
-- =====================================================================

-- F-01, F-02, F-03, F-06: 사용자 계정
CREATE TABLE IF NOT EXISTS users (
    id                  UUID PRIMARY KEY,
    email               VARCHAR(320) NOT NULL UNIQUE,   -- 소문자 정규화 저장(F-02)
    name                VARCHAR(100) NOT NULL,          -- 메일 발송용 평문(4.4)
    password_hash       TEXT NULL,                      -- Argon2id 해시(4.3), 소셜 전용은 NULL
    status              VARCHAR(30) NOT NULL,           -- PENDING_VERIFICATION | ACTIVE | DELETION_PENDING
    failed_login_count  INT NOT NULL DEFAULT 0,         -- 10회 일시 잠금, 100회 재설정 필요(F-02)
    locked_until        TIMESTAMPTZ NULL,
    deleted_at          TIMESTAMPTZ NULL,               -- 탈퇴 소프트 삭제 시각(F-06)
    purge_after         TIMESTAMPTZ NULL,               -- deleted_at + 30일(F-06)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- F-02, F-05, F-06: 구글 · Apple 연결 계정
CREATE TABLE IF NOT EXISTS user_oauth_accounts (
    id                      UUID PRIMARY KEY,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider                VARCHAR(20) NOT NULL,       -- GOOGLE | APPLE
    provider_subject        VARCHAR(255) NOT NULL,      -- 제공자 계정 고유 id(F-02)
    provider_email          VARCHAR(320) NOT NULL,
    apple_refresh_token_enc TEXT NULL,                  -- 탈퇴 시 Apple 토큰 해지용(F-06)
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (provider, provider_subject)
);

-- F-02, F-05: 로그인한 기기 목록과 세션 매핑
CREATE TABLE IF NOT EXISTS user_devices (
    id                      UUID PRIMARY KEY,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id_hash          VARCHAR(128) NOT NULL,      -- Keychain/쿠키 기기 id 해시(F-05)
    session_id              VARCHAR(128) NULL,          -- Redis 세션 id
    platform                VARCHAR(20) NOT NULL,       -- IOS | IPADOS | WEB
    model_name              VARCHAR(100) NULL,          -- 기기가 보낸 모델명(글자로만 표시, F-05)
    remember_me             BOOLEAN NOT NULL DEFAULT FALSE,
    last_active_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at              TIMESTAMPTZ NULL,           -- 기기 끊기 시각(F-05)
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_devices_user_id ON user_devices(user_id);

-- F-05: 새 제공자 연결 알림 메일의 "내가 아니다" 링크 (1회용 · 7일 만료)
CREATE TABLE IF NOT EXISTS not_me_tokens (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    oauth_account_id    UUID NOT NULL REFERENCES user_oauth_accounts(id) ON DELETE CASCADE,
    token_hmac          VARCHAR(128) NOT NULL UNIQUE,
    expires_at          TIMESTAMPTZ NOT NULL,
    used_at             TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- F-04: 메일 코드 (6자리 · 세션과 일에 묶임 · 10분 수명 · 5회 실패 · HMAC 저장)
CREATE TABLE IF NOT EXISTS mail_codes (
    id                  UUID PRIMARY KEY,
    email               VARCHAR(320) NOT NULL,
    purpose             VARCHAR(30) NOT NULL,           -- SIGNUP | PASSWORD_RESET | OAUTH_FIRST_LINK | ACCOUNT_RESTORE | ACCOUNT_RESTART
    session_binding_id  VARCHAR(128) NOT NULL,
    code_hmac           VARCHAR(128) NOT NULL,
    attempt_count       INT NOT NULL DEFAULT 0,
    resend_available_at TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_mail_codes_email_session ON mail_codes(email, session_binding_id);

-- F-04, 4.7: 반송 주소 및 코드 100회 연속 실패 24시간 차단
CREATE TABLE IF NOT EXISTS mail_bounces (
    email                   VARCHAR(320) PRIMARY KEY,
    bounced                 BOOLEAN NOT NULL DEFAULT FALSE,
    consecutive_fail_count  INT NOT NULL DEFAULT 0,
    blocked_until           TIMESTAMPTZ NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4.4: 사용자별 데이터 키 (KMS로 감싼 DEK)
CREATE TABLE IF NOT EXISTS user_data_keys (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    wrapped_dek     BYTEA NOT NULL,
    kms_key_version VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rotated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- F-43, F-47: 되묻기 대기 요청 (초기 맥락 보관 및 타 기기 선저장 중복 방지)
CREATE TABLE IF NOT EXISTS ai_pending_requests (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status              VARCHAR(20) NOT NULL,           -- AWAITING_ANSWER | COMPLETED | CANCELLED
    context_payload_enc TEXT NOT NULL,                  -- 암호화된 초기 문장 · 맥락 · 후보 · 기준 시각/시간대 · 질문/답
    result_batch_id     UUID NULL,                      -- 먼저 저장된 경우 그 묶음 id(F-47)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_pending_requests_user ON ai_pending_requests(user_id, created_at DESC);

-- F-50: 대화 기록 (워크스페이스 초기 로드 제외 · 최근 것부터 나눠 내려주기 · 휴지통 없이 삭제)
CREATE TABLE IF NOT EXISTS conversations (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status              VARCHAR(20) NOT NULL,           -- SAVED | QUERY_RESULT | AWAITING_ANSWER | PENDING_INBOX
    record_date         DATE NOT NULL,
    content_enc         TEXT NOT NULL,                  -- 사용자별 키 암호화(4.4)
    related_batch_id    UUID NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_conversations_user_created ON conversations(user_id, created_at DESC);

-- =====================================================================
-- 2. BE 3 소유 선행 테이블 (입력 화면 구성 · 컬렉션 · 휴지통 묶음)
-- =====================================================================

-- F-08, F-09, 4.7: 입력 화면 구성 (시드 작업으로 넣음)
CREATE TABLE IF NOT EXISTS input_screen_configs (
    code                VARCHAR(40) PRIMARY KEY,        -- SCHEDULE | TODO | NOTE | TIMETABLE | SCHEDULE_NOTE | TODO_NOTE | SCHEDULE_TODO
    display_order       INT NOT NULL,
    title               VARCHAR(50) NOT NULL,
    field_groups_json   JSONB NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- F-15: 휴지통 묶음 (함께 지운 반복 마스터 · 컬렉션과 소속 아이템을 하나로 묶어 복원)
CREATE TABLE IF NOT EXISTS trash_bundles (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bundle_type     VARCHAR(30) NOT NULL,               -- SINGLE_ITEM | RECURRENCE_SERIES | COLLECTION_WITH_ITEMS | BULK_ITEMS
    root_target_id  UUID NOT NULL,
    deleted_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    purge_after     TIMESTAMPTZ NOT NULL,               -- deleted_at + 30일(F-15)
    restored_at     TIMESTAMPTZ NULL
);
CREATE INDEX IF NOT EXISTS idx_trash_bundles_user_deleted ON trash_bundles(user_id, deleted_at DESC);

-- F-40: 컬렉션 (아이템은 컬렉션 하나에만 속함 · 기본 컬렉션 삭제 불가)
CREATE TABLE IF NOT EXISTS collections (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name_enc        TEXT NOT NULL,                      -- 사용자별 키 암호화(4.4)
    color           VARCHAR(20) NOT NULL,               -- 스와치 이름(비지 않음, F-40)
    icon            VARCHAR(50) NOT NULL,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    trash_bundle_id UUID NULL REFERENCES trash_bundles(id),
    deleted_at      TIMESTAMPTZ NULL,
    purge_after     TIMESTAMPTZ NULL,
    sync_seq        BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq'),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_collections_user_sync ON collections(user_id, sync_seq);

-- =====================================================================
-- 3. BE 2 소유 테이블 (아이템 · 연결 · 칸별 버전/되돌리기 · 충돌 · 묶음 쓰기)
-- =====================================================================

-- F-12, F-13, F-17, F-21, F-26, F-41, F-49: 아이템 본체 (종류 없음 · 속성으로 성격 판정)
CREATE TABLE IF NOT EXISTS items (
    id                  UUID PRIMARY KEY,               -- 기기가 정한 id(F-44)
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    collection_id       UUID NOT NULL REFERENCES collections(id),

    -- 공통 속성(F-12)
    name_enc            TEXT NULL,                      -- 암호화(필수 아님, F-12)
    tags_enc            TEXT NULL,                      -- 암호화된 JSON 배열(통째로 칸 하나, F-44)
    color               VARCHAR(20) NULL,               -- RED | ORANGE | AMBER | GREEN | TEAL | BLUE | INDIGO | PINK | GRAY

    -- 일정 속성(F-12, F-26, 4.2)
    has_schedule        BOOLEAN NOT NULL DEFAULT FALSE,
    start_at            TIMESTAMPTZ NULL,               -- 시각 일정 시작(UTC)
    end_at              TIMESTAMPTZ NULL,               -- 시각 일정 끝(UTC, 비면 길이 0 표지)
    start_date          DATE NULL,                      -- 종일 일정 시작일
    end_date            DATE NULL,                      -- 종일 일정 종료일(마지막 날의 다음 날, 4.2)
    is_all_day          BOOLEAN NOT NULL DEFAULT FALSE,
    location_enc        TEXT NULL,                      -- 암호화(4.4)

    -- 할 일 속성(F-12, F-17)
    has_todo            BOOLEAN NOT NULL DEFAULT FALSE,
    todo_status         VARCHAR(20) NULL,               -- TODO | DONE | POSTPONED | CANCELLED
    completed_at        TIMESTAMPTZ NULL,               -- todo_status와 한 칸으로 동기화(F-17, F-44)
    due_at              TIMESTAMPTZ NULL,               -- 마감(날짜만 고른 경우 기기 시간대 23:59:59의 UTC)
    priority            SMALLINT NULL CHECK (priority BETWEEN 1 AND 5),
    estimated_minutes   INT NULL CHECK (estimated_minutes > 0),

    -- 알람 속성(F-12, F-41)
    alarms_json         JSONB NULL,                     -- 기준 시각 몇 분 전 정수 배열(예: [0, 10], 통째로 칸 하나)

    -- 노트 속성(F-12, F-21)
    has_note            BOOLEAN NOT NULL DEFAULT FALSE,
    body_enc            TEXT NULL,                      -- 암호화된 마크다운 원본(칸 하나)

    -- 반복 · 시간표 유무 표시(세부 행은 BE 3 테이블에 저장)
    has_recurrence      BOOLEAN NOT NULL DEFAULT FALSE,
    has_timetable       BOOLEAN NOT NULL DEFAULT FALSE,

    -- 정리 대기 속성(F-49)
    is_pending_inbox    BOOLEAN NOT NULL DEFAULT FALSE,
    reference_time      TIMESTAMPTZ NULL,
    reference_timezone  VARCHAR(64) NULL,

    -- 소프트 삭제 · 동기화 메타(F-15, F-44)
    trash_bundle_id     UUID NULL REFERENCES trash_bundles(id),
    deleted_at          TIMESTAMPTZ NULL,
    purge_after         TIMESTAMPTZ NULL,
    sync_seq            BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq'),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_items_user_sync ON items(user_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_items_user_collection ON items(user_id, collection_id);
CREATE INDEX IF NOT EXISTS idx_items_purge_after ON items(purge_after) WHERE purge_after IS NOT NULL;

-- F-16, F-22: 아이템 연결 (양쪽 모두 요청자의 것일 때만 허용 · 같은 연결은 하나만)
CREATE TABLE IF NOT EXISTS item_links (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_item_id  UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    target_item_id  UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    link_type       VARCHAR(30) NOT NULL,               -- CREATED_FROM | REFERENCE | DERIVED_FROM | POSTPONED_TO | SPLIT_FROM
    sync_seq        BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq'),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (source_item_id, target_item_id, link_type)
);
CREATE INDEX IF NOT EXISTS idx_item_links_user_sync ON item_links(user_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_item_links_target ON item_links(target_item_id);

-- F-44, F-48: 칸 단위 마지막 변경 쓰기 이력 (칸 단위 병합 및 F-48 칸별 되돌리기 판정용)
CREATE TABLE IF NOT EXISTS item_field_versions (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id             UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    field_key           VARCHAR(100) NOT NULL,          -- NAME | BODY | LOCATION | TAGS | COLOR | COLLECTION | SCHEDULE_TIME | TODO_STATUS | DUE_AT | PRIORITY | ESTIMATED_MINUTES | ALARMS | RECURRENCE_RULE | TIMETABLE_SLOTS | EXCEPTION:YYYY-MM-DD
    last_write_id       UUID NOT NULL,
    previous_value_enc  TEXT NULL,                      -- 암호화된 직전 값(F-48 되돌리기 복원용)
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (item_id, field_key)
);

-- F-45: 풀지 않은 충돌 후보 (사용자가 고를 때까지 모든 기기에 유지)
CREATE TABLE IF NOT EXISTS conflict_candidates (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id             UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    field_key           VARCHAR(100) NOT NULL,
    candidate_value_enc TEXT NOT NULL,                  -- 암호화된 진 쓰기 값 또는 짝 없는 회차 예외 정보(4.4)
    source_write_id     UUID NOT NULL,
    resolved_at         TIMESTAMPTZ NULL,
    sync_seq            BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq'),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_conflict_candidates_user_unresolved ON conflict_candidates(user_id) WHERE resolved_at IS NULL;

-- F-43, F-49: 묶음 쓰기 요청 기록 (묶음 id 재전송 중복 방지 및 저장 상태 조회)
CREATE TABLE IF NOT EXISTS batch_requests (
    batch_id                UUID PRIMARY KEY,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    batch_type              VARCHAR(30) NOT NULL,       -- AI_MUTATION | TIMETABLE_CREATE | TODO_POSTPONE | ICS_IMPORT | BULK_DELETE
    status                  VARCHAR(20) NOT NULL,       -- COMPLETED | FAILED | UNDONE
    response_snapshot_enc   TEXT NOT NULL,              -- 재전송 시 그대로 돌려줄 암호화 응답
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_batch_requests_user ON batch_requests(user_id, created_at DESC);

-- F-41: 시스템 알림 (지워진 대상에 닿은 쓰기 · 서버 안내)
CREATE TABLE IF NOT EXISTS system_notifications (
    id                  UUID PRIMARY KEY,
    user_id             UUID NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type   VARCHAR(40) NOT NULL,           -- SERVER_NOTICE | WRITE_ON_DELETED_ITEM
    payload_enc         TEXT NOT NULL,
    sync_seq            BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq'),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4.4: 물리 삭제 로그 (DB 백업 35일 복원 뒤 삭제를 다시 적용하기 위한 무문장 기록)
CREATE TABLE IF NOT EXISTS deletion_audit_logs (
    id          UUID PRIMARY KEY,
    target_type VARCHAR(30) NOT NULL,                   -- USER | ITEM | COLLECTION | CONVERSATION
    target_id   UUID NOT NULL,
    purged_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_deletion_audit_logs_purged_at ON deletion_audit_logs(purged_at);

-- =====================================================================
-- 4. BE 3 소유 테이블 (설정 · 보기 상태 · 고정 · 반복 · 시간표 · ICS)
-- =====================================================================

-- F-46: 사용자 설정 · 지침(암호화) · 동의 상태
CREATE TABLE IF NOT EXISTS user_settings (
    user_id                     UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme                       VARCHAR(20) NOT NULL DEFAULT 'LIGHT',
    week_start_day              VARCHAR(10) NOT NULL DEFAULT 'MONDAY',
    day_start_hour              SMALLINT NOT NULL DEFAULT 7 CHECK (day_start_hour BETWEEN 0 AND 12),
    density_json                JSONB NOT NULL DEFAULT '{"month":"COMPACT","default":"STANDARD"}',
    shortcuts_json              JSONB NULL,
    ai_guideline_enc            TEXT NULL,              -- 사용자별 키 암호화(F-46, 4.4)
    ai_consent                  BOOLEAN NOT NULL DEFAULT FALSE,
    ocr_server_consent          BOOLEAN NOT NULL DEFAULT FALSE,
    visible_collection_ids_json JSONB NOT NULL DEFAULT '[]',
    sync_seq                    BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq'),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- F-26, F-29, F-32, F-37, F-46, F-51, F-52: 모듈별 보기 상태 (정렬 · 그룹 · 거르기 · 표시 요일)
CREATE TABLE IF NOT EXISTS user_view_states (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    view_key    VARCHAR(50) NOT NULL,                   -- CALENDAR_LAST_VIEW | WEB_WEEK_MODE | DAY_BOARD | TIMETABLE | TODO_MODULE | NOTE_MODULE | ARCHIVE_MODULE
    state_json  JSONB NOT NULL,
    sync_seq    BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq'),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, view_key)
);

-- F-07, F-26, F-46: 고정한 아이템 (아이템의 칸이 아니라 사용자의 보기 상태)
CREATE TABLE IF NOT EXISTS user_pinned_items (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id     UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    pinned_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_seq    BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq'),
    PRIMARY KEY (user_id, item_id)
);

-- F-23, F-24: 반복 마스터 규칙 (규칙이 정본이며 통째로 칸 하나)
CREATE TABLE IF NOT EXISTS item_recurrences (
    item_id         UUID PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rrule           VARCHAR(500) NOT NULL,              -- iCalendar RRULE(n일마다 · 매주)
    rdate_json      JSONB NOT NULL DEFAULT '[]',        -- 더하기(RDATE) 날짜 배열
    exdate_json     JSONB NOT NULL DEFAULT '[]',        -- 지우기(EXDATE) 날짜 배열
    series_root_id  UUID NOT NULL,                      -- 나뉜 마스터 사슬 묶음 id(F-16, F-24)
    effective_from  DATE NULL,                          -- 적용 시작일(F-24, F-33)
    until_date      DATE NULL,                          -- 나뉜 앞 마스터의 종료일 또는 시간표 기간 종료일
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_item_recurrences_series ON item_recurrences(user_id, series_root_id);

-- F-23: 반복 회차 예외 (RECURRENCE-ID · EXDATE, 같은 마스터와 날짜에는 하나만 남음)
CREATE TABLE IF NOT EXISTS item_exceptions (
    id                      UUID PRIMARY KEY,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    master_item_id          UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    recurrence_date         DATE NOT NULL,              -- RECURRENCE-ID 기준 날짜(날짜 하나가 칸 하나, F-44)
    is_deleted_occurrence   BOOLEAN NOT NULL DEFAULT FALSE, -- EXDATE 여부(F-23)
    exception_item_id       UUID NULL REFERENCES items(id) ON DELETE SET NULL, -- 손댄 회차 아이템(derived_from)
    modified_fields_json    JSONB NOT NULL DEFAULT '[]',-- 손댄 칸 이름 목록(나머지는 마스터를 따름, F-23)
    sync_seq                BIGINT NOT NULL DEFAULT nextval('workspace_sync_seq'),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (master_item_id, recurrence_date)
);
CREATE INDEX IF NOT EXISTS idx_item_exceptions_user_sync ON item_exceptions(user_id, sync_seq);

-- F-30, F-31, F-33: 시간표 아이템의 시간대 목록과 기간 (같은 컬렉션 내 기간·시간대 겹침 검사)
CREATE TABLE IF NOT EXISTS timetable_slots (
    id              UUID PRIMARY KEY,
    item_id         UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    collection_id   UUID NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    day_of_week     SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7), -- 1(월) ~ 7(일)
    start_time      TIME NOT NULL,                      -- 날짜·시간대 없는 시:분(4.2)
    end_time        TIME NOT NULL CHECK (end_time > start_time),
    period_start    DATE NOT NULL,                      -- 비우면 만든 날(F-30)
    period_end      DATE NULL,                          -- 비우면 끝 없음(F-30)
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_timetable_slots_collection ON timetable_slots(user_id, collection_id, day_of_week);

-- F-42: ICS 가져오기 기록 (UID 보관 및 재가져오기 시 사용자 수정 칸 보호 비교용)
CREATE TABLE IF NOT EXISTS ics_import_records (
    id                          UUID PRIMARY KEY,
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    external_uid                VARCHAR(500) NOT NULL,
    item_id                     UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    last_imported_snapshot_enc  TEXT NOT NULL,          -- 암호화된 마지막 가져온 값(4.4)
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, external_uid)
);
