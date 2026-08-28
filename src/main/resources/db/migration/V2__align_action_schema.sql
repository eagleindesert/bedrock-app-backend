-- ActionSchemaMigration(ApplicationRunner)이 매 기동마다 수행하던 스키마 보정을 이관한다.
-- 기존 DB에서만 실제로 동작하며, V1으로 새로 만든 DB에서는 모든 조건이 거짓이라 아무 일도 하지 않는다.
-- 원본의 pg_advisory_xact_lock 은 필요 없다 - Flyway가 자체 잠금으로 동시 실행을 막는다.

-- 1) 제거된 필드의 잔재
ALTER TABLE actions
    DROP COLUMN IF EXISTS end_view;

-- 2) step_order -> display_order 이관
DO
$do$
    DECLARE
        has_step_order    boolean;
        has_display_order boolean;
        bad_rows          bigint;
    BEGIN
        SELECT EXISTS (SELECT 1
                       FROM information_schema.columns
                       WHERE table_schema = current_schema()
                         AND table_name = 'action_steps'
                         AND column_name = 'step_order')
        INTO has_step_order;

        SELECT EXISTS (SELECT 1
                       FROM information_schema.columns
                       WHERE table_schema = current_schema()
                         AND table_name = 'action_steps'
                         AND column_name = 'display_order')
        INTO has_display_order;

        IF has_step_order AND NOT has_display_order THEN
            EXECUTE 'ALTER TABLE action_steps RENAME COLUMN step_order TO display_order';
            has_display_order := true;

        ELSIF has_step_order AND has_display_order THEN
            EXECUTE 'SELECT count(*) FROM action_steps
                     WHERE display_order IS NOT NULL
                       AND step_order IS NOT NULL
                       AND display_order <> step_order'
                INTO bad_rows;
            IF bad_rows > 0 THEN
                RAISE EXCEPTION
                    'action_steps에 step_order와 display_order 값이 충돌하는 행이 %건 있습니다', bad_rows;
            END IF;

            EXECUTE 'UPDATE action_steps SET display_order = step_order WHERE display_order IS NULL';
            EXECUTE 'ALTER TABLE action_steps DROP COLUMN step_order';
        END IF;

        IF NOT has_display_order THEN
            SELECT count(*) INTO bad_rows FROM action_steps;
            IF bad_rows > 0 THEN
                RAISE EXCEPTION
                    'action_steps에 %건의 행이 있으나 display_order/step_order 컬럼이 모두 없습니다', bad_rows;
            END IF;
            EXECUTE 'ALTER TABLE action_steps ADD COLUMN display_order integer';
        END IF;
    END
$do$;

-- 3) display_order 값 검증 후 NOT NULL 확정
DO
$do$
    DECLARE
        bad_rows bigint;
    BEGIN
        SELECT count(*)
        INTO bad_rows
        FROM action_steps
        WHERE display_order IS NULL
           OR display_order < 0;
        IF bad_rows > 0 THEN
            RAISE EXCEPTION 'action_steps에 NULL이거나 음수인 display_order가 %건 있습니다', bad_rows;
        END IF;

        SELECT count(*)
        INTO bad_rows
        FROM (SELECT 1
              FROM action_steps
              GROUP BY action_id, display_order
              HAVING count(*) > 1) duplicated_orders;
        IF bad_rows > 0 THEN
            RAISE EXCEPTION 'action_steps에 중복된 display_order 조합이 %건 있습니다', bad_rows;
        END IF;
    END
$do$;

ALTER TABLE action_steps
    ALTER COLUMN display_order SET NOT NULL;

-- 4) (action_id, display_order) 유니크 제약을 지연(DEFERRABLE INITIALLY DEFERRED) 형태로 통일
DO
$do$
    DECLARE
        constraint_row record;
        index_row      record;
        canonical      boolean := false;
    BEGIN
        FOR constraint_row IN
            SELECT con.conname, con.condeferrable, con.condeferred
            FROM pg_constraint con
                     JOIN pg_class rel ON rel.oid = con.conrelid
                     JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
            WHERE nsp.nspname = current_schema()
              AND rel.relname = 'action_steps'
              AND con.contype = 'u'
              AND (SELECT array_agg(att.attname::text ORDER BY att.attname)
                   FROM unnest(con.conkey) AS idx_key(attnum)
                            JOIN pg_attribute att
                                 ON att.attrelid = con.conrelid AND att.attnum = idx_key.attnum)
                = ARRAY ['action_id', 'display_order']
            LOOP
                IF constraint_row.conname = 'uk_action_steps_action_display_order'
                    AND constraint_row.condeferrable
                    AND constraint_row.condeferred THEN
                    canonical := true;
                ELSE
                    EXECUTE format('ALTER TABLE action_steps DROP CONSTRAINT %I',
                                   constraint_row.conname);
                END IF;
            END LOOP;

        -- 제약이 아닌 독립 유니크 인덱스로 걸려 있는 경우도 제거한다.
        FOR index_row IN
            SELECT idx_class.relname AS index_name
            FROM pg_index idx
                     JOIN pg_class rel ON rel.oid = idx.indrelid
                     JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                     JOIN pg_class idx_class ON idx_class.oid = idx.indexrelid
                     LEFT JOIN pg_constraint con ON con.conindid = idx_class.oid
            WHERE nsp.nspname = current_schema()
              AND rel.relname = 'action_steps'
              AND idx.indisunique
              AND idx.indexprs IS NULL
              AND idx.indpred IS NULL
              AND idx.indnatts = 2
              AND idx.indnkeyatts = 2
              AND con.oid IS NULL
              AND (SELECT array_agg(att.attname::text ORDER BY att.attname)
                   FROM unnest(idx.indkey) AS idx_key(attnum)
                            JOIN pg_attribute att
                                 ON att.attrelid = idx.indrelid AND att.attnum = idx_key.attnum)
                = ARRAY ['action_id', 'display_order']
            LOOP
                EXECUTE format('DROP INDEX %I.%I', current_schema(), index_row.index_name);
            END LOOP;

        IF NOT canonical THEN
            ALTER TABLE action_steps
                ADD CONSTRAINT uk_action_steps_action_display_order
                    UNIQUE (action_id, display_order) DEFERRABLE INITIALLY DEFERRED;
        END IF;
    END
$do$;

-- 5) 조회용 인덱스 idx_action_steps_action 정렬
DO
$do$
    DECLARE
        is_canonical boolean;
    BEGIN
        SELECT EXISTS (SELECT 1
                       FROM pg_index idx
                                JOIN pg_class rel ON rel.oid = idx.indrelid
                                JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                                JOIN pg_class idx_class ON idx_class.oid = idx.indexrelid
                                JOIN pg_am am ON am.oid = idx_class.relam
                                LEFT JOIN pg_constraint con ON con.conindid = idx_class.oid
                       WHERE nsp.nspname = current_schema()
                         AND rel.relname = 'action_steps'
                         AND idx_class.relname = 'idx_action_steps_action'
                         AND am.amname = 'btree'
                         AND NOT idx.indisunique
                         AND idx.indisvalid
                         AND idx.indisready
                         AND idx.indexprs IS NULL
                         AND idx.indpred IS NULL
                         AND idx.indnkeyatts = 2
                         AND con.oid IS NULL
                         AND (SELECT string_agg(att.attname, ',' ORDER BY idx_key.ord)
                              FROM unnest(idx.indkey) WITH ORDINALITY AS idx_key(attnum, ord)
                                       JOIN pg_attribute att
                                            ON att.attrelid = idx.indrelid
                                                AND att.attnum = idx_key.attnum
                              WHERE idx_key.ord <= idx.indnkeyatts) = 'action_id,display_order')
        INTO is_canonical;

        IF is_canonical THEN
            RETURN;
        END IF;

        IF EXISTS (SELECT 1
                   FROM pg_class idx_class
                            JOIN pg_namespace nsp ON nsp.oid = idx_class.relnamespace
                   WHERE nsp.nspname = current_schema()
                     AND idx_class.relname = 'idx_action_steps_action') THEN
            IF EXISTS (SELECT 1
                       FROM pg_constraint con
                                JOIN pg_class idx_class ON idx_class.oid = con.conindid
                                JOIN pg_namespace nsp ON nsp.oid = idx_class.relnamespace
                       WHERE nsp.nspname = current_schema()
                         AND idx_class.relname = 'idx_action_steps_action') THEN
                RAISE EXCEPTION 'idx_action_steps_action 인덱스가 제약조건에 묶여 있어 교체할 수 없습니다';
            END IF;
            EXECUTE format('DROP INDEX %I.%I', current_schema(), 'idx_action_steps_action');
        END IF;

        CREATE INDEX idx_action_steps_action ON action_steps (action_id, display_order);
    END
$do$;
