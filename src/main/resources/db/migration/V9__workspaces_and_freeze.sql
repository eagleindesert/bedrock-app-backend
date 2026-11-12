-- 공통 기반 고정 보정 1/2: 워크스페이스 단위로 데이터를 나누고, 고정 전에 정한 구조를 넣는다(2026-11-12).
-- 이유와 범위는 공통-파일.md 2.7에 있다. 동기화 트리거는 V10이 건다.
-- 행이 있는 DB에서도 돈다: 새 NOT NULL 열은 NULL로 더하고 채운 뒤 NOT NULL로 바꾼다.

-- =====================================================================
-- 0. 동기화 규칙 (V6 0절을 고친다. 모든 담당이 지킨다)
-- =====================================================================
-- 1) 워크스페이스의 데이터를 바꾸는 트랜잭션은 맨 앞에서
--    pg_advisory_xact_lock(hashtext(workspace_id::text))를 잡는다(V6의 사용자 잠금을 대신한다).
--    계정에 속한 동기화 자원(설정 · 대화 · 시스템 알림)도 MVP에서는 그 사용자의 워크스페이스 잠금을 잡는다.
-- 2) UPDATE의 sync_seq 올리기와 지울 때의 sync_tombstones는 V10 트리거가 한다. 코드가 직접 하지 않는다.
-- 3) 증분 동기화 커서와 인덱스는 (workspace_id, sync_seq)다.
-- 4) 입력 화면 구성(input_screen_configs)은 증분 동기화에서 뺀다. 스냅샷과 앱 시작 때 받는다.

-- =====================================================================
-- 1. 워크스페이스
-- =====================================================================

-- 한 계정이 여럿 가질 수 있게 둔다. MVP 동안은 계정마다 하나(UNIQUE owner_user_id)이고, 가입할 때 만든다.
CREATE TABLE IF NOT EXISTS workspaces (
    id              UUID PRIMARY KEY,
    owner_user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspaces_owner UNIQUE (owner_user_id)
);

INSERT INTO workspaces (id, owner_user_id)
SELECT gen_random_uuid(), u.id FROM users u
WHERE NOT EXISTS (SELECT 1 FROM workspaces w WHERE w.owner_user_id = u.id);

-- 워크스페이스에 속한 표: 아이템 · 컬렉션과 그에 딸린 표, 그 보기 상태, 동기화 tombstone.
-- user_id는 남긴다(공유가 오면 "쓴 사람"). 계정에 속한 표는 user_id 그대로다(공통-파일.md 2.7 표).
ALTER TABLE collections ADD COLUMN workspace_id UUID NULL;
ALTER TABLE items ADD COLUMN workspace_id UUID NULL;
ALTER TABLE item_links ADD COLUMN workspace_id UUID NULL;
ALTER TABLE item_recurrences ADD COLUMN workspace_id UUID NULL;
ALTER TABLE item_exceptions ADD COLUMN workspace_id UUID NULL;
ALTER TABLE item_timetables ADD COLUMN workspace_id UUID NULL;
ALTER TABLE timetable_slots ADD COLUMN workspace_id UUID NULL;
ALTER TABLE item_field_versions ADD COLUMN workspace_id UUID NULL;
ALTER TABLE item_field_writes ADD COLUMN workspace_id UUID NULL;
ALTER TABLE conflict_candidates ADD COLUMN workspace_id UUID NULL;
ALTER TABLE trash_bundles ADD COLUMN workspace_id UUID NULL;
ALTER TABLE user_pinned_items ADD COLUMN workspace_id UUID NULL;
ALTER TABLE ics_import_records ADD COLUMN workspace_id UUID NULL;
ALTER TABLE user_view_states ADD COLUMN workspace_id UUID NULL;
ALTER TABLE sync_tombstones ADD COLUMN workspace_id UUID NULL;

UPDATE collections t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE items t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE item_links t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE item_recurrences t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE item_exceptions t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE item_timetables t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE timetable_slots t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE item_field_versions t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE item_field_writes t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE conflict_candidates t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE trash_bundles t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE user_pinned_items t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE ics_import_records t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE user_view_states t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;
UPDATE sync_tombstones t SET workspace_id = w.id FROM workspaces w WHERE w.owner_user_id = t.user_id;

ALTER TABLE collections ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE items ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE item_links ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE item_recurrences ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE item_exceptions ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE item_timetables ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE timetable_slots ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE item_field_versions ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE item_field_writes ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE conflict_candidates ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE trash_bundles ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE user_pinned_items ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE ics_import_records ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE user_view_states ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE sync_tombstones ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE collections ADD CONSTRAINT fk_collections_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE items ADD CONSTRAINT fk_items_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE item_links ADD CONSTRAINT fk_item_links_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE item_recurrences ADD CONSTRAINT fk_item_recurrences_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE item_exceptions ADD CONSTRAINT fk_item_exceptions_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE item_timetables ADD CONSTRAINT fk_item_timetables_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE timetable_slots ADD CONSTRAINT fk_timetable_slots_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE item_field_versions ADD CONSTRAINT fk_item_field_versions_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE item_field_writes ADD CONSTRAINT fk_item_field_writes_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE conflict_candidates ADD CONSTRAINT fk_conflict_candidates_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE trash_bundles ADD CONSTRAINT fk_trash_bundles_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE user_pinned_items ADD CONSTRAINT fk_user_pinned_items_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE ics_import_records ADD CONSTRAINT fk_ics_import_records_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE user_view_states ADD CONSTRAINT fk_user_view_states_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
ALTER TABLE sync_tombstones ADD CONSTRAINT fk_sync_tombstones_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;

-- 증분 동기화 인덱스를 워크스페이스 단위로 바꾼다.
DROP INDEX IF EXISTS idx_collections_user_sync;
DROP INDEX IF EXISTS idx_items_user_sync;
DROP INDEX IF EXISTS idx_item_links_user_sync;
DROP INDEX IF EXISTS idx_item_exceptions_user_sync;
DROP INDEX IF EXISTS idx_conflict_candidates_user_sync;
DROP INDEX IF EXISTS idx_trash_bundles_user_sync;
DROP INDEX IF EXISTS idx_ics_import_records_user_sync;
DROP INDEX IF EXISTS idx_sync_tombstones_user_sync;
CREATE INDEX IF NOT EXISTS idx_collections_workspace_sync ON collections(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_items_workspace_sync ON items(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_item_links_workspace_sync ON item_links(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_item_exceptions_workspace_sync ON item_exceptions(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_conflict_candidates_workspace_sync ON conflict_candidates(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_trash_bundles_workspace_sync ON trash_bundles(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_ics_import_records_workspace_sync ON ics_import_records(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_sync_tombstones_workspace_sync ON sync_tombstones(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_user_view_states_workspace_sync ON user_view_states(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_user_pinned_items_workspace_sync ON user_pinned_items(workspace_id, sync_seq);
CREATE INDEX IF NOT EXISTS idx_items_workspace_collection ON items(workspace_id, collection_id);
-- 계정에 속한 동기화 자원도 같은 스트림으로 읽는다. 대화 · 설정은 (user_id, sync_seq) 인덱스나 키가 이미 있다.
CREATE INDEX IF NOT EXISTS idx_system_notifications_user_sync ON system_notifications(user_id, sync_seq);

-- 보기 상태는 (워크스페이스, 사용자, 보기)마다 하나다.
ALTER TABLE user_view_states DROP CONSTRAINT user_view_states_user_id_view_key_key;
ALTER TABLE user_view_states ADD CONSTRAINT uq_user_view_states_view UNIQUE (workspace_id, user_id, view_key);

-- =====================================================================
-- 2. 보일 컬렉션: 설정의 "켠 목록"을 모듈별 보기 상태의 "끈 목록"으로 옮긴다
-- =====================================================================
-- 캘린더(CALENDAR_LAST_VIEW)와 시간표(TIMETABLE)가 각자 state_json.hiddenCollectionIds를 갖는다.
-- 빈 목록이면 모두 보이므로 새 컬렉션은 보이는 채 생긴다.
-- 옮기는 값: 켠 목록이 비어 있지 않은 계정의, 켠 목록에 없는 컬렉션.
-- 켠 목록이 비어 있으면 옮기지 않는다(끈 목록 없음 = 모두 보임). 이 열을 쓴 클라이언트가 없어서
-- 지금 DB의 []는 "모두 끔"이 아니라 "정한 적 없음"이다. 모두 끈 것으로 옮기면 배포 뒤 컬렉션이 모두 숨는다.
INSERT INTO user_view_states (id, user_id, workspace_id, view_key, state_json)
SELECT gen_random_uuid(), s.user_id, w.id, v.view_key, jsonb_build_object('hiddenCollectionIds', h.hidden)
FROM user_settings s
JOIN workspaces w ON w.owner_user_id = s.user_id
CROSS JOIN (VALUES ('CALENDAR_LAST_VIEW'), ('TIMETABLE')) AS v(view_key)
CROSS JOIN LATERAL (
    SELECT jsonb_agg(c.id::text ORDER BY c.id) AS hidden
    FROM collections c
    WHERE c.user_id = s.user_id
      AND NOT (s.visible_collection_ids_json ? c.id::text)
) h
WHERE jsonb_array_length(s.visible_collection_ids_json) > 0
  AND h.hidden IS NOT NULL
ON CONFLICT (workspace_id, user_id, view_key)
DO UPDATE SET state_json = user_view_states.state_json || EXCLUDED.state_json;

ALTER TABLE user_settings DROP COLUMN visible_collection_ids_json;

-- =====================================================================
-- 3. 서버 공지는 사용자마다 한 줄이다
-- =====================================================================
-- 사용자 없는 공지는 어느 잠금에도 들지 않아 커서를 건너뛴다. 사용자마다 펼쳐 넣고 user_id를 NOT NULL로 둔다.
INSERT INTO system_notifications (id, user_id, notification_type, payload_enc, created_at)
SELECT gen_random_uuid(), u.id, n.notification_type, n.payload_enc, n.created_at
FROM system_notifications n CROSS JOIN users u
WHERE n.user_id IS NULL;
DELETE FROM system_notifications WHERE user_id IS NULL;
ALTER TABLE system_notifications ALTER COLUMN user_id SET NOT NULL;

-- =====================================================================
-- 4. 아이템 만든 시각과 시간표 사슬
-- =====================================================================
-- items.created_at은 기기가 만든 시각이다(ItemCreateSpec.createdAt, 서버 시각보다 미래면 서버 시각).
-- 날짜 없는 아이템의 만든 날을 이 값으로 정한다(F-26). 감사용 서버 시각은 server_created_at이다.
ALTER TABLE items ADD COLUMN server_created_at TIMESTAMPTZ NULL;
UPDATE items SET server_created_at = created_at;
ALTER TABLE items ALTER COLUMN server_created_at SET NOT NULL;
ALTER TABLE items ALTER COLUMN server_created_at SET DEFAULT NOW();

-- 시간표 아이템도 적용 시작일로 나뉜다(F-33). 사슬 id는 나뉜 앞뒤 아이템이 함께 쓰고, 처음 아이템은 자기 id다.
ALTER TABLE item_timetables ADD COLUMN series_root_id UUID NULL;
ALTER TABLE item_timetables ADD COLUMN effective_from DATE NULL;
UPDATE item_timetables SET series_root_id = item_id;
ALTER TABLE item_timetables ALTER COLUMN series_root_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_item_timetables_series ON item_timetables(workspace_id, series_root_id);

-- 지운 회차의 정본은 item_exceptions(is_deleted_occurrence)뿐이다.
-- item_recurrences.exdate_json · rdate_json은 ICS로 들어온 값만 담고 반복 규칙 칸(RECURRENCE_RULE)과 함께 바뀐다.
COMMENT ON COLUMN item_recurrences.exdate_json IS 'ICS로 들어온 EXDATE만. 사용자가 지운 회차는 item_exceptions.is_deleted_occurrence';
COMMENT ON COLUMN item_recurrences.rdate_json IS 'ICS로 들어온 RDATE만';
