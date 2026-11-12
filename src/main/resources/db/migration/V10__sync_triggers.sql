-- 공통 기반 고정 보정 2/2: 동기화 규칙을 DB 트리거로 강제한다(2026-11-12).
-- 이유와 범위는 공통-파일.md 2.7에 있다.
-- V6 0절의 규칙 2 · 3 · 4(UPDATE마다 sync_seq, 지우면 tombstone, 아이템을 이루는 행이 바뀌면 아이템 번호)를
-- 세 담당의 코드 관례로 두면 한 경로라도 빠질 때 다른 기기에 영영 가지 않는다. CASCADE · SET NULL처럼
-- DB가 스스로 바꾸는 행은 관례로 덮을 수도 없다. 그래서 여기서 강제하고, 코드는 직접 하지 않는다.

-- =====================================================================
-- 1. UPDATE마다 새 번호
-- =====================================================================
CREATE OR REPLACE FUNCTION bedrock_bump_sync_seq() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.sync_seq := nextval('workspace_sync_seq');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_collections_sync_seq BEFORE UPDATE ON collections FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_items_sync_seq BEFORE UPDATE ON items FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_item_links_sync_seq BEFORE UPDATE ON item_links FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_item_exceptions_sync_seq BEFORE UPDATE ON item_exceptions FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_conflict_candidates_sync_seq BEFORE UPDATE ON conflict_candidates FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_trash_bundles_sync_seq BEFORE UPDATE ON trash_bundles FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_ics_import_records_sync_seq BEFORE UPDATE ON ics_import_records FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_user_pinned_items_sync_seq BEFORE UPDATE ON user_pinned_items FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_user_view_states_sync_seq BEFORE UPDATE ON user_view_states FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_user_settings_sync_seq BEFORE UPDATE ON user_settings FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_conversations_sync_seq BEFORE UPDATE ON conversations FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
CREATE TRIGGER trg_system_notifications_sync_seq BEFORE UPDATE ON system_notifications FOR EACH ROW EXECUTE FUNCTION bedrock_bump_sync_seq();
-- input_screen_configs는 증분 동기화에서 뺐으므로(V9 0절 4) 트리거를 걸지 않는다.

-- =====================================================================
-- 2. 아이템을 이루는 행이 바뀌면 아이템 번호를 올린다 (V6 규칙 4 + 충돌 후보 · 칸 버전)
-- =====================================================================
-- ItemDto에 함께 실리는 반복 · 시간표 · 시간대 · 칸 버전 · 충돌 후보가 바뀌면 아이템을 다시 보내야 한다.
-- items의 BEFORE UPDATE 트리거가 새 번호를 준다. 아이템이 함께 지워지는 중이면 고칠 행이 없어 아무 일도 없다.
CREATE OR REPLACE FUNCTION bedrock_touch_parent_item() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        UPDATE items SET sync_seq = sync_seq WHERE id = OLD.item_id;
    ELSE
        UPDATE items SET sync_seq = sync_seq WHERE id = NEW.item_id;
        IF TG_OP = 'UPDATE' AND NEW.item_id IS DISTINCT FROM OLD.item_id THEN
            UPDATE items SET sync_seq = sync_seq WHERE id = OLD.item_id;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_item_recurrences_touch_item AFTER INSERT OR UPDATE OR DELETE ON item_recurrences FOR EACH ROW EXECUTE FUNCTION bedrock_touch_parent_item();
CREATE TRIGGER trg_item_timetables_touch_item AFTER INSERT OR UPDATE OR DELETE ON item_timetables FOR EACH ROW EXECUTE FUNCTION bedrock_touch_parent_item();
CREATE TRIGGER trg_timetable_slots_touch_item AFTER INSERT OR UPDATE OR DELETE ON timetable_slots FOR EACH ROW EXECUTE FUNCTION bedrock_touch_parent_item();
CREATE TRIGGER trg_item_field_versions_touch_item AFTER INSERT OR UPDATE OR DELETE ON item_field_versions FOR EACH ROW EXECUTE FUNCTION bedrock_touch_parent_item();
CREATE TRIGGER trg_conflict_candidates_touch_item AFTER INSERT OR UPDATE OR DELETE ON conflict_candidates FOR EACH ROW EXECUTE FUNCTION bedrock_touch_parent_item();

-- =====================================================================
-- 3. 지우면 tombstone 한 줄
-- =====================================================================
-- 인자: 자원 종류(API WorkspaceResourceType), resourceId로 쓸 열 이름.
-- 워크스페이스 표는 그 행의 workspace_id, 계정 표(대화 · 시스템 알림)는 MVP의 계정 워크스페이스에 남긴다.
-- 계정이나 워크스페이스가 함께 지워지는 중이면(탈퇴 정리) 남길 곳이 없으므로 건너뛴다.
CREATE OR REPLACE FUNCTION bedrock_record_tombstone() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    old_row      jsonb := to_jsonb(OLD);
    owner_id     uuid := (old_row ->> 'user_id')::uuid;
    target_ws    uuid := (old_row ->> 'workspace_id')::uuid;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = owner_id) THEN
        RETURN NULL;
    END IF;
    IF target_ws IS NULL THEN
        SELECT id INTO target_ws FROM workspaces WHERE owner_user_id = owner_id;
    END IF;
    IF target_ws IS NULL OR NOT EXISTS (SELECT 1 FROM workspaces WHERE id = target_ws) THEN
        RETURN NULL;
    END IF;
    INSERT INTO sync_tombstones (user_id, workspace_id, resource_type, resource_id)
    VALUES (owner_id, target_ws, TG_ARGV[0], old_row ->> TG_ARGV[1]);
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_collections_tombstone AFTER DELETE ON collections FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('COLLECTION', 'id');
CREATE TRIGGER trg_items_tombstone AFTER DELETE ON items FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('ITEM', 'id');
CREATE TRIGGER trg_item_links_tombstone AFTER DELETE ON item_links FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('LINK', 'id');
CREATE TRIGGER trg_item_exceptions_tombstone AFTER DELETE ON item_exceptions FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('OCCURRENCE_EXCEPTION', 'id');
CREATE TRIGGER trg_trash_bundles_tombstone AFTER DELETE ON trash_bundles FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('TRASH_BUNDLE', 'id');
CREATE TRIGGER trg_ics_import_records_tombstone AFTER DELETE ON ics_import_records FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('ICS_IMPORT_RECORD', 'id');
CREATE TRIGGER trg_user_pinned_items_tombstone AFTER DELETE ON user_pinned_items FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('PIN', 'item_id');
CREATE TRIGGER trg_user_view_states_tombstone AFTER DELETE ON user_view_states FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('VIEW_STATE', 'view_key');
CREATE TRIGGER trg_conversations_tombstone AFTER DELETE ON conversations FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('CONVERSATION', 'id');
CREATE TRIGGER trg_system_notifications_tombstone AFTER DELETE ON system_notifications FOR EACH ROW EXECUTE FUNCTION bedrock_record_tombstone('SYSTEM_NOTIFICATION', 'id');
-- 충돌 후보는 지우지 않고 resolved_at을 찍는다(UPDATE). 그룹이 다 풀리면 API가 그 번호로 CONFLICT DELETE 변경을 낸다.
-- 아이템과 함께 지워진 후보는 ITEM tombstone으로 충분하다. 설정 행은 계정과 함께만 지워진다.
