-- 이미 적용된 V1~V3의 체크섬을 유지하면서 새 명세로 전환한다.
-- 구형 모델의 데이터가 있으면 자동 삭제/암호화 추정 이관을 하지 않고 중단한다.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM users
        UNION ALL SELECT 1 FROM collections
        UNION ALL SELECT 1 FROM collection_users
        UNION ALL SELECT 1 FROM items
        UNION ALL SELECT 1 FROM actions
        UNION ALL SELECT 1 FROM action_steps
        UNION ALL SELECT 1 FROM debug_test
    ) THEN
        RAISE EXCEPTION
            '구형 모델 데이터가 있습니다. 백업과 별도 데이터 이관 계획을 완료한 새 DB에서 공통 스키마를 적용하세요.';
    END IF;
END $$;

DROP TABLE action_steps;
DROP TABLE actions;
DROP TABLE items;
DROP TABLE collection_users;
DROP TABLE collections;
DROP TABLE users;
DROP TABLE debug_test;
