-- 공통 기반 보정: 기능 명세 v0.0.6(2026-11-12)의 종류별 기본 알림.
-- 근거: F-41 · F-46. 이유와 범위는 공통-파일.md 2.6에 있다.

-- 종류별 기본 알림. 키는 넷이고 값은 items.alarms_json과 같은 뜻의 목록이다(기준 시각 몇 분 전, 0은 정시).
--   timedEvent   시각 일정             기준 시각 = 시작
--   allDayEvent  종일 일정             기준 시각 = 그날의 하루 시작 시각(day_start_hour)
--   timedTodo    시각을 고른 할 일      기준 시각 = 마감
--   dateOnlyTodo 날짜만 고른 할 일      기준 시각 = 그날의 하루 시작 시각
-- 서버는 저장만 한다. 아이템을 만들 때 알람을 따로 정하지 않았으면 기기가 이 값을 아이템의 알람 칸에 채운다.
-- 일정이면서 할 일이면 일정의 값을 쓴다. 기본값을 바꿔도 이미 만든 아이템은 바뀌지 않는다.
-- 처음 값(가입 때)은 서버가 넣는다: 시각 일정 10분 전, 나머지는 기준 시각 정시.
ALTER TABLE user_settings ADD COLUMN default_alarms_json JSONB NOT NULL DEFAULT '{"timedEvent":[10],"allDayEvent":[0],"timedTodo":[0],"dateOnlyTodo":[0]}';
-- 키가 빠지면 jsonb_typeof가 NULL이 되어 CHECK를 통과하므로 ?&로 네 키가 다 있는지 먼저 본다.
ALTER TABLE user_settings ADD CONSTRAINT ck_user_settings_default_alarms CHECK (
    default_alarms_json ?& ARRAY['timedEvent', 'allDayEvent', 'timedTodo', 'dateOnlyTodo']
    AND jsonb_typeof(default_alarms_json -> 'timedEvent') = 'array'
    AND jsonb_typeof(default_alarms_json -> 'allDayEvent') = 'array'
    AND jsonb_typeof(default_alarms_json -> 'timedTodo') = 'array'
    AND jsonb_typeof(default_alarms_json -> 'dateOnlyTodo') = 'array'
    AND default_alarms_json - ARRAY['timedEvent', 'allDayEvent', 'timedTodo', 'dateOnlyTodo'] = '{}'::jsonb
    AND NOT jsonb_path_exists(default_alarms_json, '$.*[*] ? (@.type() != "number" || @ < 0 || @ != @.floor())')
);
