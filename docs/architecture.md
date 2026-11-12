# 공통 기반과 새 모델

`공통-파일.md` 2026-11-01판의 1단계 공통 파일과 전체 저장 모델을 적용한다.
루트 패키지는 기존 저장소의 `com.bedrock.app`를 유지한다.
기존 액션·임의 JSON 기반 아이템·종류별 공유 컬렉션 모델과 API는 제거했다.
이번 범위는 공통 기반과 새 모델이며 실제 담당 기능은 각 패키지에서 구현한다.

## 요구사항 대응

| 요구사항 | 구현 위치 / 동작 |
| --- | --- |
| 전체 스키마 | `V5__init_workspace_schema.sql`: 26개 테이블, workspace_sync_seq, FK·인덱스·CHECK |
| API 명세 v1.0 보정 | `V6__align_with_api_v1.sql`: 쓰기 기록·삭제 흔적·칸 쓰기 이력·묶음 결과·시간표 기간 5개 테이블 추가, 묶음 키·회차 예외 키·시간대 열 정리 (`공통-파일.md` 2.4) |
| 아이템 시간대 · 날짜 마감 | `V7__item_time_zone_and_due_date.sql`: `items.time_zone` · `items.due_date`(`due_at`과 배타), `user_settings.time_zone`, `ItemTimeZones` · `@IanaTimeZone` (`공통-파일.md` 2.5) |
| 새 저장 모델 | 담당별 domain 패키지의 31개 JPA 엔티티, 고정 아이템 · 묶음 요청 · 묶음 결과 · 칸 버전 · 쓰기 기록의 복합 키 |
| 이력 보존과 전환 | V1~V3 불변, V4는 구형 데이터 유무 검사 후 빈 구형 테이블만 제거 |
| 종류별 기본 알림 | `V8__user_settings_default_alarms.sql`: `user_settings.default_alarms_json`(네 종류, 가입 때 기본값, CHECK) (`공통-파일.md` 2.6) |
| Flyway + JPA 검증 | `application.yml`: validate, out-of-order, UTC, open-in-view 비활성화 |
| 공통 응답·페이지 | `ApiResponse`, `CursorPageResponse`, `ApiResponseAdvice` |
| 오류 계약 | `ErrorCode`, `BusinessException`, 담당별 Enum 3개, `GlobalExceptionHandler` |
| 공통 도메인 | `BaseTimeEntity`, `SwatchColor`, `TodoStatus`, `LinkType` |
| 반복 규칙 꼴 | `RecurrenceRule`: n일마다 · 매주 · 매월 · 매년 같은 날짜, 없는 날 건너뛰기(RFC 5545), 회차 날짜 계산 (`공통-파일.md` 4.4) |
| 담당 간 호출 | `common.port`의 5개 인터페이스와 DTO |
| 초기 Stub | `PortStubAutoConfiguration`: 구현 빈이 없을 때 등록, 호출 시 503 |
| 세션 사용자 | `@CurrentUser SessionPrincipal`, UUID 계정·기기, Redis 직렬화 |
| 세션 수명 | `SessionAuthenticationService`: 인증 성공 후 세션 ID·CSRF 교체, 로그아웃 |
| 공통 설정 | Security, CORS, Session, Password, Jackson(Instant 초 단위), Async, Persistence |
| 웹 탭 검증 | `WebTabAccountFilter`: 불일치·잘못된 UUID·중복 헤더를 ACCOUNT_MISMATCH로 거부 |
| 로그·캐시 | `EndpointAccessLogFilter`: no-store, IP·메서드·라우트·상태·시간·사용자 UUID |
| 로그 보관 | 콘솔 + 파일, 20MB, gzip, 365일, 파일 종류별 최대 10GB |
| 개발 Compose | `docker-compose.yaml`: 기본 인프라, app 프로필로 백엔드 추가 |
| 배포 | Noble 기반 JRE, linux/amd64, 비루트 사용자, 호스트 Nginx의 loopback 포트 |
| 난독화 | `proguard.pro`, `bootJarObfuscate`: 앱 클래스만 난독화한 뒤 BootJar 재조립 |
| 이미지 배포 | build-push.sh, docker-compose-deployment.yaml, .env.example, start.sh |

## 담당 경계

| 담당 | 패키지 | 추가 마이그레이션 |
| --- | --- | --- |
| BE 1 | auth, mail, crypto, ai, conversation, ocr | V101부터 |
| BE 2 | item, link, sync, conflict, batch, undo | V201부터 |
| BE 3 | collection, setting, trash, recurrence, timetable, ics | V301부터 |

공통 기반 확정 후에는 자기 패키지와 담당 에러 Enum 안에서 작업한다.
다른 담당의 서비스나 저장소를 직접 주입하지 않고 `common.port`를 사용한다.

- BE 1: `UserCryptoPort` 구현.
- BE 2: `ItemBatchWritePort`, `ConflictRegistryPort` 구현.
- BE 3: `ScheduleMutationPort` 구현.
- BE 2·3: 각 작업을 조합하는 `WorkspaceLifecyclePort` 구현 빈 하나를 제공.

Stub은 연결 계약이며 암호화·저장·삭제의 성공을 가장하지 않는다.
호출하면 `503 FEATURE_NOT_READY`가 발생하고 실제 구현 빈을 등록하면 해당 Stub은 자동으로 제외된다.
패키지의 `package-info.java`는 담당 범위를 표시한다.

## DB 전환과 JPA 모델

이미 적용된 V1~V3 파일의 체크섬을 깨지 않기 위해 명세의 전체 초기 스키마는 V5로 도입한다.
빈 DB에서는 V1~V3 → V4 정리 → V5 새 스키마 순서로 적용된다.
V4는 구형 users, collections, collection_users, items, actions, action_steps, debug_test에
행이 하나라도 있으면 예외를 발생시켜 중단한다. 기존 데이터를 자동 삭제하거나 평문을 암호문으로 간주해 옮기지 않는다.
기존 데이터가 있다면 백업과 별도 이관 계획을 완료한 새 DB를 사용해야 한다.

최종 스키마에는 구형 컬럼·테이블이나 호환용 legacy 스키마가 없다.
로컬 PostgreSQL 17은 `bedrock-local` 프로젝트의 `postgres_data_v2` 볼륨을 사용한다.
구형 PostgreSQL 15 볼륨을 새 이미지에 연결하거나 삭제하지 않는다.

새 모델은 UUID 식별자와 사용자 소유자 ID를 사용하며, ID는 호출자가 명시적으로 제공한다.
관계는 UUID 필드로 표현하고 FK·유일성·CHECK 제약은 정본 SQL에서 강제한다.
시간대가 있는 시각은 Instant, 날짜는 LocalDate, 시간표 시간은 LocalTime이다.
Instant는 초 단위다. `InstantSecondsConverter`(autoApply)가 저장할 때 초 아래를 버리고, 감사 시각도 초 단위로 찍는다.
API는 `JacksonConfig`가 받을 때 밀리초 · 오프셋을 읽어 초 아래를 버리고, 보낼 때 늘 `YYYY-MM-DDTHH:mm:ssZ`로 쓴다.
JSONB는 각 필드에 맞는 배열·맵으로 표현하며, 자유 구조인 입력 화면 field_groups_json은 Object다.
`*_enc`는 암호문 저장 필드다. 실제 암복호화는 UserCryptoPort 구현에서 수행한다.

생성·수정 시각은 JPA Auditing을 적용한다. 둘 다 있는 엔티티는 BaseTimeEntity를 상속한다.
sync_seq는 삽입 시 DB 시퀀스 값을 읽어 온다. 이후 변경 시의 새 동기화 번호 배정과 칸 단위 병합은
BE 2의 실제 쓰기 구현에서 처리해야 한다. 빌더는 저장 모델 생성 도구이며 기능별 유효성 검증을 대신하지 않는다.

## 공통 HTTP·보안

성공 응답은 `{"success":true,"data":...,"error":null}`이다.
실패 응답은 `{"success":false,"data":null,"error":{"code":"...","message":"...","details":...}}`이다.
204 응답에는 본문이 없다. 없는 대상과 타인 소유 대상에는 같은 RESOURCE_NOT_FOUND를 사용한다.

현재 실제 업무 API는 제공하지 않는다. `/api/auth/csrf`는 쓰기 요청을 위한 사전 세션과 토큰을 제공한다.
BE 1은 실제 자격 증명 검증 후 SessionAuthenticationService.signIn을 호출한다.
이 메서드는 세션 ID와 CSRF 토큰을 교체하며 인증되지 않은 사용자의 신원을 검증하는 기능은 하지 않는다.
컨트롤러는 `@CurrentUser SessionPrincipal`로 인증 주체를 받는다.

세션은 Redis의 `bedrock:session:v2`에 저장되고 무활동 30분 뒤 만료된다.
쿠키는 BEDROCK_SESSION, HttpOnly, SameSite=Lax, 호스트 전용이고 Secure 기본값은 true다.
로컬 HTTP에서만 COOKIE_SECURE=false를 사용한다. 비밀번호 인코더는 Argon2id(19MiB, 반복 2, 병렬도 1)다.
웹 탭 계정 헤더가 세션과 다르면 CSRF/컨트롤러 처리 전에 ACCOUNT_MISMATCH로 거절한다.

로그에는 본문·쿼리·쿠키·토큰·예외 메시지·SQL 바인딩 값을 넣지 않는다.
경로 토큰도 배제하기 위해 `/api/items/{id}` 같은 매핑 템플릿만 기록한다.
MVC에 도달하기 전 거절되거나 매핑이 없는 요청은 `[unmapped]`로 기록한다.
Tomcat native forwarded-header 처리가 신뢰한 IP를 사용하며 루프백 프록시의 전달 헤더만 추가로 읽는다.
호스트 Nginx는 외부 클라이언트의 전달 헤더를 자기 값으로 덮어써야 한다.

## 난독화

ProGuard는 앱 클래스에만 적용한다. Boot 로더와 외부 라이브러리는 의존성 JAR로 유지한다.
Spring 설정·컨트롤러·JPA·DTO·Enum·세션·포트 등 반사 접근 경계는 보존하고 내부 서비스 이름을 변경한다.
서비스 빈에는 명시적인 이름을 지정한다. 매핑 파일은 `build/obfuscation/mapping.txt`에 남으며 이미지에는 넣지 않는다.

참조한 공식 문서:
[Spring Session / Boot](https://docs.spring.io/spring-boot/4.0/reference/web/spring-session.html),
[ProGuard Spring Boot 예제](https://github.com/Guardsquare/proguard/blob/master/examples/spring-boot/build.gradle).
