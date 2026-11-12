-- 공통 기반 보정: 기능 명세 v0.0.6(2026-11-12)의 아이템 시간대와 날짜만 고른 마감.
-- 근거: F-12 · F-17 · F-23 · F-26 · F-46 · 4.2. 이유와 범위는 공통-파일.md 2.5에 있다.

-- 아이템의 표준 시간대(IANA, 예: Asia/Seoul). 비면 떠 있는 시각이다(보는 기기의 시간대로 읽는다).
-- 새 아이템은 계정의 기본 시간대(user_settings.time_zone)를 받는다. MVP의 앱은 늘 값을 넣는다.
-- 시각 값(start_at · end_at · due_at)의 벽시계, 반복 회차 날짜(recurrence_date · rdate · exdate ·
-- until_date · effective_from), 날짜 없는 아이템의 만든 날을 이 시간대로 정한다.
-- 종일 값(start_date · end_date) · due_date · 시간표 시간대(timetable_slots)는 시간대 없이 날짜와 시각이다.
-- 칸 키는 TIME_ZONE이다.
ALTER TABLE items ADD COLUMN time_zone VARCHAR(64) NULL;

-- 날짜만 고른 마감. 시각을 고른 마감은 due_at이고 둘 가운데 하나만 있다.
-- 두 열은 칸 키 DUE_AT 하나로 버전 · 충돌을 다룬다(한 기기가 날짜, 다른 기기가 시각을 골라도 둘이 함께 서지 않게).
ALTER TABLE items ADD COLUMN due_date DATE NULL;
ALTER TABLE items ADD CONSTRAINT ck_items_due_at_or_due_date CHECK (due_at IS NULL OR due_date IS NULL);

-- 계정의 기본 시간대. 처음 값은 가입한 기기의 시간대이고(WorkspaceLifecyclePort), 설정에서 바꾼다.
-- 바꾸면 그 뒤에 만든 아이템만 새 값을 받는다. 화면은 이 값과 상관없이 보는 기기의 시간대로 보인다.
-- 이미 있는 행은 Asia/Seoul로 채우고 기본값을 지운다. 새 행은 가입 처리가 값을 넣어야 한다.
ALTER TABLE user_settings ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Seoul';
ALTER TABLE user_settings ALTER COLUMN time_zone DROP DEFAULT;
