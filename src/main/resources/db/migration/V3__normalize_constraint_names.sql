-- Hibernate가 자동 생성한 해시 이름(uk6dotkott2..., fkg24laery... )을 V1의 이름 규칙에 맞춘다.
-- 이렇게 해야 baseline으로 넘어온 기존 DB와 V1으로 새로 만든 DB의 스키마가 완전히 같아진다.
-- 이름이 이미 규칙에 맞는 DB에서는 아무 일도 하지 않는다.

-- users(email) 유니크 제약 -> uk_users_email
DO
$do$
    DECLARE
        current_name text;
    BEGIN
        SELECT con.conname
        INTO current_name
        FROM pg_constraint con
                 JOIN pg_class rel ON rel.oid = con.conrelid
                 JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = current_schema()
          AND rel.relname = 'users'
          AND con.contype = 'u'
          AND con.conname <> 'uk_users_email'
          AND (SELECT array_agg(att.attname::text)
               FROM unnest(con.conkey) AS con_key(attnum)
                        JOIN pg_attribute att
                             ON att.attrelid = con.conrelid AND att.attnum = con_key.attnum)
            = ARRAY ['email']
        LIMIT 1;

        IF current_name IS NULL THEN
            RETURN;
        END IF;

        IF EXISTS (SELECT 1
                   FROM pg_constraint con
                            JOIN pg_class rel ON rel.oid = con.conrelid
                            JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                   WHERE nsp.nspname = current_schema()
                     AND rel.relname = 'users'
                     AND con.conname = 'uk_users_email') THEN
            RETURN;
        END IF;

        EXECUTE format('ALTER TABLE users RENAME CONSTRAINT %I TO uk_users_email', current_name);
    END
$do$;

-- action_steps(action_id) 외래키 -> fk_action_steps_action
DO
$do$
    DECLARE
        current_name text;
    BEGIN
        SELECT con.conname
        INTO current_name
        FROM pg_constraint con
                 JOIN pg_class rel ON rel.oid = con.conrelid
                 JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = current_schema()
          AND rel.relname = 'action_steps'
          AND con.contype = 'f'
          AND con.conname <> 'fk_action_steps_action'
          AND (SELECT array_agg(att.attname::text)
               FROM unnest(con.conkey) AS con_key(attnum)
                        JOIN pg_attribute att
                             ON att.attrelid = con.conrelid AND att.attnum = con_key.attnum)
            = ARRAY ['action_id']
        LIMIT 1;

        IF current_name IS NULL THEN
            RETURN;
        END IF;

        IF EXISTS (SELECT 1
                   FROM pg_constraint con
                            JOIN pg_class rel ON rel.oid = con.conrelid
                            JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                   WHERE nsp.nspname = current_schema()
                     AND rel.relname = 'action_steps'
                     AND con.conname = 'fk_action_steps_action') THEN
            RETURN;
        END IF;

        EXECUTE format('ALTER TABLE action_steps RENAME CONSTRAINT %I TO fk_action_steps_action',
                       current_name);
    END
$do$;
