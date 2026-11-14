# 베드락 Backend API Specification (v1.1-draft)

> Base URL: `/api/v1` (운영 호스트 미정)
>
> 인증 방식: Spring Session + Redis 서버 세션, `Cookie: BEDROCK_SESSION=<sessionId>`
>
> Last Updated: 2026-11-12

---

## 🆕 v1.1-draft 확정 사항 요약

- **v1.1(2026-11-12)**: 뉴 기능 명세 v0.0.6과 백엔드 공통 기반 고정 결정을 담았다. 바뀐 것은 끝의 변경 기록에 있다.
- **계약 상태**: `뉴 기능 명세 v0.0.6`·`공통 파일`·`FE·BE 구현 분배안`을 바탕으로 정리한 API 명세다.
- **프로젝트명·경로**: 제품명은 베드락(`Bedrock`)이다. 서비스 이름 후보 스위피는 미정이다. 기본 경로는 `/api/v1`이며 운영 도메인은 미정이다.
- **인증**: Spring Session + Redis의 서버 세션을 사용한다. 쿠키 이름은 `BEDROCK_SESSION`, `HttpOnly`·운영 `Secure`·`SameSite=Lax`·API 호스트 전용이며 `Domain` 속성을 설정하지 않는다. 웹은 `credentials: "include"`, iOS는 이 기기 전용 Keychain에 세션 자격을 보관한다. 액세스·리프레시 JWT를 별도로 발급하지 않는다.
  - 익명 세션은 30분 무활동으로 끝난다. 일반 웹 로그인(`platform=WEB`, `rememberMe=false`)도 사용자 입력이 만든 요청 없이 30분이면 끝난다. iOS와 유지 로그인의 만료는 30일 미사용이다.
  - 일반 웹 세션의 30분 무활동 만료는 별도 활동 시각 기준으로 판정하며, `X-Bedrock-User-Activity: 1` 헤더가 포함된 사용자 조작 요청에서만 활동 시각을 갱신한다. 백그라운드 자동 동기화(`GET /workspace/changes`)나 자동 세션 확인(`GET /auth/session`)은 활동으로 세지 않는다.
  - 로그인·복구 등 인증 상태가 달라지면 세션 ID를 새로 발급한다.
- **공통 응답**: JSON은 `ApiResponse<T>`의 `success`·`data`·`error`를 사용한다. 성공의 `error`와 실패의 `data`는 `null`이다. 최상위 `message`·`code`를 추가하지 않는다.
- **계정·메일**: 가입과 재설정은 계정 유무를 응답·처리 시간으로 드러내지 않는다. 인증 전 가입은 로그인 가능한 계정이 아니다. 비밀번호 검사는 코드 확인 뒤에 한다. 비밀번호는 8자 이상이며 64자 초과도 받고, 흔하거나 유출된 값은 거부한다.
  - 코드는 숫자 6자리·10분·코드당 오답 5회·재발송 간격 60초다. 주소당 1시간 5통·24시간 20통, 주소별 연속 오답 100회면 24시간 발송·확인을 막는다. 세션·목적에 묶고 HMAC으로 저장하며 메일은 비동기로 보낸다.
- **소유·개인정보**: 없는 대상과 다른 사용자의 대상은 같은 `404 RESOURCE_NOT_FOUND`다. 알려진 본인 대상의 물리 삭제 거절만 `410 WRITE_REJECTED_PURGED`로 구분한다. 사용자 글은 사용자별 키로 암호화하고 검색·정렬·글 비교·위키링크 해석은 FE가 한다. KMS 키·암호문은 API에 노출하지 않는다.
- **시간·성격**: 시각은 UTC `Z`, 종일은 날짜와 배타적 끝 날짜를 사용한다. 시간표는 시간대 없는 요일·`HH:mm`이다. 아이템은 표준 시간대(`timeZone`, IANA)를 갖고 만들 때 계정 기본 시간대를 받는다. 날짜만 고른 마감은 `dueDate`다. 일정·할 일·노트·시간표는 속성으로 판정하고 아이템 종류로 저장하지 않는다.
- **오프라인·합치기**: iOS만 오프라인 쓰기를 보관한다. 웹은 탭 메모리에서 읽고 연결이 있을 때만 쓴다. 아이템 ID·단건 `writeId`·묶음 `batchId`는 기기에서 정해 재전송 때 유지한다. 다른 칸은 합치고 같은 칸의 진 쓰기는 후보로 남긴다. 일반 만료·재설정·기기 끊김의 로컬 데이터 처리는 구분한다.
  - 동기화 커서는 워크스페이스별 쓰기 직렬화 경계에서 산출하여 미커밋 변경이 증분 조회에서 누락되지 않도록 보장한다.
- **원자 저장·AI·되돌리기**: AI 해석은 저장과 분리한다. FE 검증 뒤 한 문장의 생성·수정·삭제·회차 예외·반복 나누기를 한 번에 저장한다. 질문이 남으면 다른 결과도 저장하지 않는다. 저장 응답이 없으면 기존 묶음을 조회하거나 같은 ID로 재전송한다.
  - 한 번 완료된 AI 요청(`aiRequestId`)은 기기·묶음 ID가 달라도 한 번만 저장하며, 이후 결과를 되돌리더라도 재실행·중복 저장하지 않는다.
  - 대화 기록 삭제 후에는 해당 기록에 연결된 결과의 되돌리기를 허용하지 않는다.
- **FE 전용 처리**: 홈 묶음·캘린더/시간표 회차·검색·필터·정렬·빈 시간·조회 실행·위키링크 파싱·ICS 파싱/내보내기·로컬 알람에는 별도 서버 실행 API를 만들지 않는다. 그날·그달 할 일 처리는 대상마다 상태 쓰기를 하나씩 낸다.
- **기본 한도와 운영값**: 공통 설정의 기본은 AI 15초·사용자당 하루 100회, OCR 20초·하루 20회다. 실제 운영값·집계 기준은 환경 설정으로 조정한다.
- **삭제·로그**: 휴지통·탈퇴 유예는 각각 30일이다. 대화 기록 삭제는 휴지통을 거치지 않는다. 사용자 글·비밀번호·코드·세션·OAuth 토큰·AI 입출력·캡처를 로그에 남기지 않는다. 모든 API는 `Cache-Control: no-store`이고 웹은 사용자 데이터를 브라우저 저장소에 남기지 않는다.

**공통 Headers**

| 헤더                      | 값                            | 필수                                                       |
| ------------------------- | ----------------------------- | ---------------------------------------------------------- |
| `Cookie`                  | `BEDROCK_SESSION=<sessionId>` | ✅ 인증 API 및 익명 작업을 이어 갈 때                      |
| `Content-Type`            | `application/json`            | ✅ JSON 요청 본문이 있을 때                                |
| `X-Bedrock-Account-Id`    | 탭이 보관한 계정 UUID         | ✅ 웹의 인증된 데이터 접근, iOS는 생략                     |
| `X-Bedrock-User-Activity` | `1`                           | - 사용자 입력이 만든 요청에만 전송 (웹 무활동 타이머 갱신) |

> 아래 엔드포인트는 Base URL 뒤에 붙인다. 모든 인증 API에는 세션·웹 계정 일치·소유 검사를 적용한다. 공통 헤더는 상세마다 반복하지 않는다. 웹 Origin 허용 목록은 앱과 개발 주소만이며 운영 도메인은 미정이다.

---

## 📋 API 엔드포인트 총괄표

| #   | 분류                | Method | Endpoint                            | 인증 | 선행 조건                                                     | 설명                                   | 업데이트 날짜 |
| --- | ------------------- | ------ | ----------------------------------- | ---- | ------------------------------------------------------------- | -------------------------------------- | ------------- |
| 1   | Auth                | GET    | `/auth/session`                     | ❌   | -                                                             | 세션 초기화·현재 상태 확인             | 2026-11-10    |
| 2   | Auth                | POST   | `/auth/signup`                      | ❌   | -                                                             | 이메일 가입 시작                       | 2026-11-10    |
| 3   | Auth                | POST   | `/auth/login`                       | ❌   | -                                                             | 이메일 로그인                          | 2026-11-10    |
| 4   | Auth                | POST   | `/auth/oauth/{provider}/start`      | ❌   | -                                                             | 구글·Apple 로그인 시작                 | 2026-11-10    |
| 5   | Auth                | POST   | `/auth/oauth/{provider}/complete`   | ❌   | 같은 세션에서 OAuth 시작 완료                                 | 제공자 자격 검증·로그인                | 2026-11-10    |
| 6   | Auth                | POST   | `/auth/password-reset`              | ❌   | -                                                             | 비밀번호 재설정 시작                   | 2026-11-10    |
| 7   | Auth                | POST   | `/auth/mail-codes/complete`         | ❌   | 같은 세션에 유효한 가입·재설정·OAuth·탈퇴 유예 확인 작업 존재 | 메일 코드 확인·해당 작업 완료          | 2026-11-10    |
| 8   | Auth                | POST   | `/auth/mail-codes/resend`           | ❌   | 같은 익명 세션의 대기 작업                                    | 현재 작업의 코드 재발송                | 2026-11-10    |
| 9   | Auth                | POST   | `/auth/account-recovery`            | ❌   | 같은 세션에서 해당 이메일의 메일 코드 확인 완료               | 탈퇴 유예 계정 되살리기·새로 시작      | 2026-11-10    |
| 10  | Auth                | POST   | `/auth/logout`                      | ✅   | -                                                             | 현재 세션 로그아웃                     | 2026-11-10    |
| 11  | Auth                | GET    | `/auth/devices`                     | ✅   | -                                                             | 현재 로그인한 기기 목록                | 2026-11-10    |
| 12  | Auth                | DELETE | `/auth/devices/{deviceId}`          | ✅   | -                                                             | 기기 하나 끊기                         | 2026-11-10    |
| 13  | Auth                | DELETE | `/auth/devices`                     | ✅   | -                                                             | 현재 기기를 제외한 모든 기기 끊기      | 2026-11-10    |
| 14  | Auth                | POST   | `/auth/not-me/inspect`              | ❌   | -                                                             | 내가 아니다 링크 확인 정보             | 2026-11-10    |
| 15  | Auth                | POST   | `/auth/not-me/revoke`               | ❌   | -                                                             | 새 제공자 연결 해제·모든 로그인 종료   | 2026-11-10    |
| 16  | Auth                | PATCH  | `/users/me`                         | ✅   | -                                                             | 내 프로필 수정                         | 2026-11-10    |
| 17  | Auth                | DELETE | `/users/me`                         | ✅   | -                                                             | 탈퇴                                   | 2026-11-10    |
| 18  | Workspace & Sync    | GET    | `/workspace`                        | ✅   | -                                                             | 워크스페이스 전체 스냅샷               | 2026-11-10    |
| 19  | Workspace & Sync    | GET    | `/workspace/changes`                | ✅   | -                                                             | 커서 이후 변경 받기                    | 2026-11-10    |
| 20  | Item & Link         | POST   | `/items`                            | ✅   | -                                                             | 아이템 하나 만들기                     | 2026-11-10    |
| 21  | Item & Link         | GET    | `/items/{itemId}`                   | ✅   | -                                                             | 저장된 아이템 정본 조회                | 2026-11-10    |
| 22  | Item & Link         | PATCH  | `/items/{itemId}`                   | ✅   | -                                                             | 칸 단위 수정·회차 예외·반복 전체 변경  | 2026-11-10    |
| 23  | Item & Link         | POST   | `/items/trash`                      | ✅   | -                                                             | 아이템 하나·여럿·반복 범위 소프트 삭제 | 2026-11-10    |
| 24  | Link                | POST   | `/links`                            | ✅   | -                                                             | 출처 연결 걸기                         | 2026-11-10    |
| 25  | Link                | DELETE | `/links/{linkId}`                   | ✅   | -                                                             | 출처 연결 끊기                         | 2026-11-10    |
| 26  | Collection          | POST   | `/collections`                      | ✅   | -                                                             | 컬렉션 만들기                          | 2026-11-10    |
| 27  | Collection          | PATCH  | `/collections/{collectionId}`       | ✅   | -                                                             | 컬렉션 이름·색·아이콘 수정             | 2026-11-10    |
| 28  | Collection          | POST   | `/collections/{collectionId}/trash` | ✅   | -                                                             | 컬렉션과 소속 아이템 함께 삭제         | 2026-11-10    |
| 29  | Trash               | GET    | `/trash`                            | ✅   | -                                                             | 휴지통 묶음 목록                       | 2026-11-10    |
| 30  | Trash               | POST   | `/trash/{trashBundleId}/restore`    | ✅   | -                                                             | 함께 지운 대상 복구                    | 2026-11-10    |
| 31  | Atomic Batch        | POST   | `/batches`                          | ✅   | -                                                             | 한 묶음 원자 저장                      | 2026-11-10    |
| 32  | Atomic Batch        | GET    | `/batches/{batchId}`                | ✅   | -                                                             | 기존 묶음 저장 상태 확인               | 2026-11-10    |
| 33  | Conflict            | GET    | `/conflicts`                        | ✅   | -                                                             | 풀지 않은 충돌 목록                    | 2026-11-10    |
| 34  | Conflict            | POST   | `/conflicts/resolve`                | ✅   | -                                                             | 후보 선택·태그 합집합·예외 맞추기      | 2026-11-10    |
| 35  | Undo                | POST   | `/undo`                             | ✅   | -                                                             | 선택한 결과 하나 되돌리기              | 2026-11-10    |
| 36  | Settings & View     | GET    | `/settings`                         | ✅   | -                                                             | 사용자 설정 조회                       | 2026-11-10    |
| 37  | Settings & View     | PATCH  | `/settings`                         | ✅   | -                                                             | 사용자 설정 일부 변경                  | 2026-11-10    |
| 38  | Settings & View     | GET    | `/view-states`                      | ✅   | -                                                             | 모듈별 보기 상태 조회                  | 2026-11-10    |
| 39  | Settings & View     | PUT    | `/view-states/{viewKey}`            | ✅   | -                                                             | 보기 상태 저장                         | 2026-11-10    |
| 40  | Settings & View     | PUT    | `/pins/{itemId}`                    | ✅   | -                                                             | 아이템 고정                            | 2026-11-10    |
| 41  | Settings & View     | DELETE | `/pins/{itemId}`                    | ✅   | -                                                             | 고정 해제                              | 2026-11-10    |
| 42  | Settings & View     | GET    | `/input-screen-configs`             | ✅   | -                                                             | 입력 화면 구성 조회                    | 2026-11-10    |
| 43  | AI Interpretation   | POST   | `/ai/interpretations`               | ✅   | AI 해석 동의 `aiConsent=true`                                 | 문장 해석·정리 대기 재시도             | 2026-11-10    |
| 44  | AI Interpretation   | GET    | `/ai/requests/{requestId}`          | ✅   | -                                                             | 대기 질문·해석 결과·선저장 결과 조회   | 2026-11-10    |
| 45  | AI Interpretation   | POST   | `/ai/requests/{requestId}/answers`  | ✅   | AI 동의 유지, 해당 요청이 답을 기다림                         | 되묻기에 이어 답하기                   | 2026-11-10    |
| 46  | Conversation        | GET    | `/conversations`                    | ✅   | -                                                             | 최근 대화 기록 목록                    | 2026-11-10    |
| 47  | Conversation        | PUT    | `/conversations/{conversationId}`   | ✅   | -                                                             | 문장·질문·답·결과 요약 기록            | 2026-11-10    |
| 48  | Conversation        | GET    | `/conversations/{conversationId}`   | ✅   | -                                                             | 대화 기록 상세                         | 2026-11-10    |
| 49  | Conversation        | DELETE | `/conversations/{conversationId}`   | ✅   | -                                                             | 대화 기록 하나 바로 삭제               | 2026-11-10    |
| 50  | Conversation        | DELETE | `/conversations`                    | ✅   | -                                                             | 대화 기록 전체 바로 삭제               | 2026-11-10    |
| 51  | Timetable OCR       | POST   | `/ocr/timetables`                   | ✅   | 서버 캡처 인식 동의 `ocrServerConsent=true`                   | 시간표 캡처 서버 인식                  | 2026-11-10    |
| 52  | ICS Import          | POST   | `/ics/imports`                      | ✅   | -                                                             | FE가 해석·비교한 ICS 묶음 저장         | 2026-11-10    |
| 53  | System Notification | GET    | `/notifications/system`             | ✅   | -                                                             | 서버 안내·삭제 대상 쓰기 안내 조회     | 2026-11-10    |

> **범례**: `✅`는 로그인 세션 필수, `❌`는 비로그인 허용이다. `❌`라도 진행 중인 코드·OAuth·복구 작업의 익명 세션은 필요할 수 있다. `-`는 추가 선행 조건이 없다는 뜻이다.

---

## 1. 계정·로그인 (Auth)

> F-01~F-06·F-46. `❌`는 로그인 불필요라는 뜻이다. 코드·OAuth·복구를 이어 가는 요청에는 해당 작업을 시작한 익명 세션 쿠키가 필요하다. 인증 상태가 바뀌면 세션 ID를 회전한다.

### GET `/auth/session` — 세션 초기화·현재 상태 확인

- **인증 요구사항**: ❌
- **설명**: 익명 세션을 준비하거나 현재 로그인 상태를 확인한다. 자동 확인·백그라운드 동기화는 웹 무활동 시간을 연장하지 않는다. 요청 본문은 없습니다.

**Response 200 OK**

| 필드                 | 타입         | 설명                |
| -------------------- | ------------ | ------------------- |
| `success`            | `Boolean`    | `true`              |
| `data`               | `SessionDto` | 공통 DTO 참조       |
| `data.authenticated` | `Boolean`    | 로그인 여부         |
| `data.user`          | `UserDto?`   | 미로그인이면 `null` |
| `error`              | `null`       | 오류 없음           |

---

### POST `/auth/signup` — 이메일 가입 시작

- **인증 요구사항**: ❌
- **설명**: 이메일·이름을 익명 세션에 임시 보관하고 메일을 비동기로 발송한다. 인증 전 가입을 로그인 가능한 계정으로 만들지 않는다. 기존 활성 계정·없는 계정의 HTTP 상태·본문·처리 시간을 구분하지 않는다. 같은 이메일의 미완료 가입을 다시 시작하면 앞선 가입과 코드를 폐기한다.

**Request Body (`SignupStartRequest`)**

| 필드     | 타입               | 필수 | Validation              | 설명                           |
| -------- | ------------------ | ---- | ----------------------- | ------------------------------ |
| `email`  | `String`           | ✅   | 이메일 형식, 최대 320자 | 소문자 정규화                  |
| `name`   | `String`           | ✅   | 공백만 불가, 최대 100자 | 인증 전 메일에는 포함하지 않음 |
| `device` | `DeviceContextDto` | ✅   | 플랫폼 필수             | 가입한 기기 정보               |

**Response 202 Accepted**

| 필드      | 타입               | 설명          |
| --------- | ------------------ | ------------- |
| `success` | `Boolean`          | `true`        |
| `data`    | `MailChallengeDto` | 공통 DTO 참조 |
| `error`   | `null`             | 오류 없음     |

**예외 응답**

- **400 Bad Request**: 형식 오류 (`WRITE_REJECTED_VALIDATION`)
- **429 Too Many Requests**: 요청자·서비스 발송 한도, 주소별 내부 상태를 노출하지 않는 응답 정책은 확인 필요 (`MAIL_CODE_RATE_LIMITED`)

---

### POST `/auth/login` — 이메일 로그인

- **인증 요구사항**: ❌
- **설명**: 이메일·비밀번호를 검증한다. 실패·일시 잠금·100회 실패 잠금은 같은 `INVALID_CREDENTIALS`로 응답한다. 탈퇴 유예 계정은 자격 확인 후에도 메일 코드를 확인해야 하며 정식 세션은 발급하지 않는다.

**Request Body (`LoginRequest`)**

| 필드         | 타입               | 필수 | Validation   | 설명                                   |
| ------------ | ------------------ | ---- | ------------ | -------------------------------------- |
| `email`      | `String`           | ✅   | 이메일 형식  | 소문자 정규화                          |
| `password`   | `String`           | ✅   | 필수         | 원문을 로그에 남기지 않음              |
| `rememberMe` | `Boolean`          | -    | 기본 `false` | 웹 로그인 유지. iOS·iPadOS는 항상 유지 |
| `device`     | `DeviceContextDto` | ✅   | 플랫폼 필수  | 기기 식별은 권한 근거로 쓰지 않음      |

**Response 200 OK**

| 필드            | 타입           | 설명                       |
| --------------- | -------------- | -------------------------- |
| `success`       | `Boolean`      | `true`                     |
| `data`          | `AuthStepDto`  | 공통 DTO 참조              |
| `data.nextStep` | `AuthNextStep` | 현재 인증 흐름의 다음 단계 |
| `error`         | `null`         | 오류 없음                  |

**예외 응답**

- **401 Unauthorized**: 자격 오류 또는 잠금 (`INVALID_CREDENTIALS`)

---

### POST `/auth/oauth/{provider}/start` — 구글·Apple 로그인 시작

- **인증 요구사항**: ❌
- **설명**: 제공자 인가에 필요한 세션 바인딩 값을 만든다. Apple `nonce`와 웹 `state`는 서버가 만들고 한 번만 검증한다. 리다이렉트 주소는 서버의 허용 목록에서만 선택한다.

**Path Variables**

| 변수       | 타입            | 설명                  |
| ---------- | --------------- | --------------------- |
| `provider` | `OAuthProvider` | `GOOGLE` 또는 `APPLE` |

**Request Body (`OAuthStartRequest`)**

| 필드          | 타입               | 필수 | Validation    | 설명               |
| ------------- | ------------------ | ---- | ------------- | ------------------ |
| `device`      | `DeviceContextDto` | ✅   | 플랫폼 필수   | 시작 플랫폼        |
| `rememberMe`  | `Boolean`          | -    | 기본 `false`  | 웹 로그인 유지     |
| `redirectUri` | `String`           | -    | 등록된 주소만 | 운영 도메인은 미정 |

**Response 200 OK**

| 필드      | 타입                | 설명          |
| --------- | ------------------- | ------------- |
| `success` | `Boolean`           | `true`        |
| `data`    | `OAuthChallengeDto` | 공통 DTO 참조 |
| `error`   | `null`              | 오류 없음     |

**예외 응답**

- **400 Bad Request**: 제공자·리다이렉트 주소 오류 (`WRITE_REJECTED_VALIDATION`)

---

### POST `/auth/oauth/{provider}/complete` — 제공자 자격 검증·로그인

- **인증 요구사항**: ❌
- **선행 조건**: 같은 세션에서 OAuth 시작 완료
- **설명**: ID 토큰의 서명·발급자·대상·만료를 검증하고 제공자 계정 ID로 기존 연결을 찾는다. 첫 연결의 이메일 소유 확인 규칙은 기능 명세 F-02를 따른다. 추가 코드가 필요하면 메일 확인 단계로 응답한다. Apple의 빈 이메일 토큰은 거부하며 `email_verified`는 `true` 또는 문자열 `"true"`만 참이다. Google은 확인된 Gmail 또는 `hd`가 있는 Workspace 주소, Apple은 확인된 `icloud.com`·`me.com`·`mac.com`·비공개 릴레이 주소일 때 첫 연결 코드를 생략한다. 그 외는 메일 코드가 필요하다. 소유가 확인된 소문자 이메일끼리만 계정을 잇는다. Apple 릴레이 주소와 다른 로그인 방식 계정의 연결 방법은 미정이다.

**Path Variables**

| 변수       | 타입            | 설명                 |
| ---------- | --------------- | -------------------- |
| `provider` | `OAuthProvider` | 인가를 시작한 제공자 |

**Request Body (`OAuthCompleteRequest`)**

| 필드                | 타입     | 필수 | Validation              | 설명                                    |
| ------------------- | -------- | ---- | ----------------------- | --------------------------------------- |
| `idToken`           | `String` | ✅   | 제공자 토큰             | 로그 금지                               |
| `authorizationCode` | `String` | -    | Apple 토큰 교환 시 필요 | Apple 탈퇴 토큰 해지에 필요한 토큰 확보 |
| `state`             | `String` | -    | 웹에서 필수, 1회용      | 시작 세션과 일치                        |
| `nonce`             | `String` | -    | Apple에서 필수          | ID 토큰과 시작 세션에 묶어 검증         |

**Response 200 OK**

| 필드            | 타입           | 설명                       |
| --------------- | -------------- | -------------------------- |
| `success`       | `Boolean`      | `true`                     |
| `data`          | `AuthStepDto`  | 공통 DTO 참조              |
| `data.nextStep` | `AuthNextStep` | 현재 인증 흐름의 다음 단계 |
| `error`         | `null`         | 오류 없음                  |

**예외 응답**

- **401 Unauthorized**: 토큰·바인딩 검증 실패 (`INVALID_CREDENTIALS`)

---

### POST `/auth/password-reset` — 비밀번호 재설정 시작

- **인증 요구사항**: ❌
- **설명**: 계정 존재 여부와 무관하게 같은 응답을 먼저 반환한다. 존재하는 계정에만 코드를 비동기로 발송한다. 소셜 전용 계정도 비밀번호를 만들 수 있다.

**Request Body (`PasswordResetStartRequest`)**

| 필드     | 타입               | 필수 | Validation  | 설명          |
| -------- | ------------------ | ---- | ----------- | ------------- |
| `email`  | `String`           | ✅   | 이메일 형식 | 소문자 정규화 |
| `device` | `DeviceContextDto` | ✅   | 플랫폼 필수 | 요청 플랫폼   |

**Response 202 Accepted**

| 필드      | 타입               | 설명          |
| --------- | ------------------ | ------------- |
| `success` | `Boolean`          | `true`        |
| `data`    | `MailChallengeDto` | 공통 DTO 참조 |
| `error`   | `null`             | 오류 없음     |

**예외 응답**

- **429 Too Many Requests**: 발송 제한 (`MAIL_CODE_RATE_LIMITED`)

---

### POST `/auth/mail-codes/complete` — 메일 코드 확인·해당 작업 완료

- **인증 요구사항**: ❌
- **선행 조건**: 같은 세션에 유효한 가입·재설정·OAuth·탈퇴 유예 확인 작업 존재
- **설명**: 코드의 세션·목적·기한·시도 횟수를 먼저 확인한다. 가입·재설정은 코드가 맞을 때만 비밀번호를 검사·해시한다. 가입·OAuth 완료는 정식 세션을 발급한다. 재설정 완료는 모든 세션을 끝내고 새 세션 없이 로그인 화면으로 돌려보낸다. 탈퇴 유예 계정이면 선택 권한만 부여하고 `RECOVERY_CHOICE_REQUIRED`를 반환한다. 코드는 계정 확인에 한 번만 사용한다. 비밀번호의 필수 여부·길이 검사도 코드 확인 전에 수행하지 않는다.

**Request Body (`MailCodeCompleteRequest`)**

| 필드       | 타입     | 필수 | Validation                                     | 설명                                            |
| ---------- | -------- | ---- | ---------------------------------------------- | ----------------------------------------------- |
| `code`     | `String` | ✅   | 숫자 6자리                                     | 앞의 0 보존                                     |
| `password` | `String` | -    | 가입·재설정에서 필수, 8자 이상, 64자 초과 허용 | 흔하거나 유출된 비밀번호 거부. 상한은 확인 필요 |

**Response 200 OK**

| 필드            | 타입           | 설명                       |
| --------------- | -------------- | -------------------------- |
| `success`       | `Boolean`      | `true`                     |
| `data`          | `AuthStepDto`  | 공통 DTO 참조              |
| `data.nextStep` | `AuthNextStep` | 현재 인증 흐름의 다음 단계 |
| `error`         | `null`         | 오류 없음                  |

**예외 응답**

- **400 Bad Request**: 틀린 코드·만료·목적 불일치 (`INVALID_MAIL_CODE`)
- **400 Bad Request**: 코드 확인 후 비밀번호 규칙 위반 (`WRITE_REJECTED_VALIDATION`)
- **429 Too Many Requests**: 메일 코드 한도 (`MAIL_CODE_RATE_LIMITED`)

---

### POST `/auth/mail-codes/resend` — 현재 작업의 코드 재발송

- **인증 요구사항**: ❌
- **선행 조건**: 같은 익명 세션의 대기 작업
- **설명**: 앞선 발송 후 60초부터 가능하다. 이전 코드를 무효화하고 새 코드를 비동기로 발송한다. 코드 수명은 10분이며 요청만으로 연장하지 않는다. 요청 본문은 없습니다.

**Response 202 Accepted**

| 필드      | 타입               | 설명          |
| --------- | ------------------ | ------------- |
| `success` | `Boolean`          | `true`        |
| `data`    | `MailChallengeDto` | 공통 DTO 참조 |
| `error`   | `null`             | 오류 없음     |

**예외 응답**

- **400 Bad Request**: 대기 작업 없음 (`INVALID_MAIL_CODE`)
- **429 Too Many Requests**: 60초 미경과·발송 한도 (`MAIL_CODE_RATE_LIMITED`)

---

### POST `/auth/account-recovery` — 탈퇴 유예 계정 되살리기·새로 시작

- **인증 요구사항**: ❌
- **선행 조건**: 같은 세션에서 해당 이메일의 메일 코드 확인 완료
- **설명**: `RESTORE`는 30일 유예 계정을 되살린다. `RESTART`는 옛 계정·데이터·키를 즉시 물리 삭제하고 새 계정과 기본 워크스페이스를 만든다. 비밀번호 재설정으로 들어온 경우에는 되살리기만 허용하고 비밀번호 변경 후 모든 로그인을 종료한다. 취소하면 이 API를 호출하지 않는다. 복구 선택 권한은 1회용이다.

**Request Body (`AccountRecoveryRequest`)**

| 필드        | 타입             | 필수 | Validation                            | 설명                                               |
| ----------- | ---------------- | ---- | ------------------------------------- | -------------------------------------------------- |
| `action`    | `RecoveryAction` | ✅   | `RESTORE` 또는 `RESTART`              | 원래 흐름에 따라 허용 범위 검증                    |
| `confirmed` | `Boolean`        | ✅   | `true`                                | 새로 시작은 되돌릴 수 없음                         |
| `name`      | `String`         | -    | 새 계정에 필요한 경우, 최대 100자     | 프로필 보충                                        |
| `password`  | `String`         | -    | 원래 가입·재설정 흐름에서 필요한 경우 | 코드 확인 후 적용. 세부 계정 복원 정책은 확인 필요 |

**Response 200 OK**

| 필드            | 타입           | 설명                       |
| --------------- | -------------- | -------------------------- |
| `success`       | `Boolean`      | `true`                     |
| `data`          | `AuthStepDto`  | 공통 DTO 참조              |
| `data.nextStep` | `AuthNextStep` | 현재 인증 흐름의 다음 단계 |
| `error`         | `null`         | 오류 없음                  |

**예외 응답**

- **400 Bad Request**: 확인·원래 흐름에 필요한 값 누락 (`WRITE_REJECTED_VALIDATION`)
- **400 Bad Request**: 확인 권한 만료 (`INVALID_MAIL_CODE`)

---

### POST `/auth/logout` — 현재 세션 로그아웃

- **인증 요구사항**: ✅
- **설명**: 현재 세션을 무효화하고 세션 쿠키를 만료시킨다. 보내지 않은 쓰기를 보내거나 버릴지 고르는 일은 FE가 호출 전에 처리한다. 요청 본문은 없습니다.

**Response 200 OK**

| 필드      | 타입      | 설명                   |
| --------- | --------- | ---------------------- |
| `success` | `Boolean` | `true`                 |
| `data`    | `null`    | 성공, 추가 데이터 없음 |
| `error`   | `null`    | 오류 없음              |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

### GET `/auth/devices` — 현재 로그인한 기기 목록

- **인증 요구사항**: ✅
- **설명**: 플랫폼·모델·마지막 활동 시각·현재 기기 여부를 반환한다. 모델은 기기가 제공한 표시용 문자열이다. 요청 본문은 없습니다.

**Response 200 OK**

| 필드      | 타입              | 설명          |
| --------- | ----------------- | ------------- |
| `success` | `Boolean`         | `true`        |
| `data`    | `List<DeviceDto>` | 공통 DTO 참조 |
| `error`   | `null`            | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

### DELETE `/auth/devices/{deviceId}` — 기기 하나 끊기

- **인증 요구사항**: ✅
- **설명**: 고른 기기에 연결된 세션을 종료한다. 현재 기기를 고르면 현재 세션도 종료한다. 끊긴 기기는 다음 요청에서 사본과 보내지 않은 쓰기를 지운다. 요청 본문은 없습니다.

**Path Variables**

| 변수       | 타입   | 설명                                                           |
| ---------- | ------ | -------------------------------------------------------------- |
| `deviceId` | `UUID` | 기기 목록의 서버 레코드 ID. Keychain·쿠키의 기기 식별값과 다름 |

**Response 200 OK**

| 필드      | 타입      | 설명                   |
| --------- | --------- | ---------------------- |
| `success` | `Boolean` | `true`                 |
| `data`    | `null`    | 성공, 추가 데이터 없음 |
| `error`   | `null`    | 오류 없음              |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)

---

### DELETE `/auth/devices` — 현재 기기를 제외한 모든 기기 끊기

- **인증 요구사항**: ✅
- **설명**: 현재 기기의 로그인은 유지하고 나머지 기기 세션을 종료한다. 요청 본문은 없습니다.

**Response 200 OK**

| 필드      | 타입      | 설명                   |
| --------- | --------- | ---------------------- |
| `success` | `Boolean` | `true`                 |
| `data`    | `null`    | 성공, 추가 데이터 없음 |
| `error`   | `null`    | 오류 없음              |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

### POST `/auth/not-me/inspect` — 내가 아니다 링크 확인 정보

- **인증 요구사항**: ❌
- **설명**: 메일 토큰을 검증하고 확인 화면에 필요한 제공자·만료 시각만 반환한다. 토큰을 소비하거나 제공자를 끊지 않는다. 메일 링크를 GET으로 여는 행위 자체에는 부작용이 없다.

**Request Body (`NotMeInspectRequest`)**

| 필드    | 타입     | 필수 | Validation           | 설명                               |
| ------- | -------- | ---- | -------------------- | ---------------------------------- |
| `token` | `String` | ✅   | 1회용 토큰, 7일 이내 | URL 경로·접근 로그에 포함하지 않음 |

**Response 200 OK**

| 필드      | 타입           | 설명          |
| --------- | -------------- | ------------- |
| `success` | `Boolean`      | `true`        |
| `data`    | `NotMeInfoDto` | 공통 DTO 참조 |
| `error`   | `null`         | 오류 없음     |

**예외 응답**

- **400 Bad Request**: 토큰 무효·만료·사용됨 (`INVALID_NOT_ME_TOKEN`)

---

### POST `/auth/not-me/revoke` — 새 제공자 연결 해제·모든 로그인 종료

- **인증 요구사항**: ❌
- **설명**: 확인 화면에서 사용자가 선택했을 때만 호출한다. 토큰의 제공자 연결을 끊고 모든 기기의 세션을 종료한다. 토큰은 원자적으로 한 번 소비한다.

**Request Body (`NotMeRevokeRequest`)**

| 필드        | 타입      | 필수 | Validation  | 설명                |
| ----------- | --------- | ---- | ----------- | ------------------- |
| `token`     | `String`  | ✅   | 유효한 토큰 | 검사 요청과 같은 값 |
| `confirmed` | `Boolean` | ✅   | `true`      | 명시적인 끊기 선택  |

**Response 200 OK**

| 필드      | 타입      | 설명                   |
| --------- | --------- | ---------------------- |
| `success` | `Boolean` | `true`                 |
| `data`    | `null`    | 성공, 추가 데이터 없음 |
| `error`   | `null`    | 오류 없음              |

**예외 응답**

- **400 Bad Request**: 토큰 무효·만료·사용됨 (`INVALID_NOT_ME_TOKEN`)

---

### PATCH `/users/me` — 내 프로필 수정

- **인증 요구사항**: ✅
- **설명**: 계정 프로필의 이름을 수정한다. 이메일 변경은 원문에 정의되지 않아 이 API에 포함하지 않는다.

**Request Body (`ProfilePatchRequest`)**

| 필드   | 타입     | 필수 | Validation              | 설명                                         |
| ------ | -------- | ---- | ----------------------- | -------------------------------------------- |
| `name` | `String` | ✅   | 공백만 불가, 최대 100자 | 계정 사용자 이름. 메일 발송을 위해 평문 저장 |

**Response 200 OK**

| 필드      | 타입      | 설명          |
| --------- | --------- | ------------- |
| `success` | `Boolean` | `true`        |
| `data`    | `UserDto` | 공통 DTO 참조 |
| `error`   | `null`    | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **400 Bad Request**: 이름 검증 실패 (`WRITE_REJECTED_VALIDATION`)

---

### DELETE `/users/me` — 탈퇴

- **인증 요구사항**: ✅
- **설명**: 호출 전 FE가 30일 뒤 삭제를 확인받는다. 계정을 소프트 삭제하고 모든 세션을 종료하며 Apple 토큰을 해지한다. 응답의 삭제 예정 시각은 서버 기준이다. 물리 삭제는 내부 작업으로 실행한다. 요청 본문은 없습니다.

**Response 200 OK**

| 필드      | 타입                 | 설명          |
| --------- | -------------------- | ------------- |
| `success` | `Boolean`            | `true`        |
| `data`    | `AccountDeletionDto` | 공통 DTO 참조 |
| `error`   | `null`               | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

## 2. 워크스페이스·동기화 (Workspace & Sync)

> F-44·F-50. 웹의 워크스페이스 받기·쓰기에는 `X-Bedrock-Account-Id`를 반드시 보낸다. 서버의 정본을 내려주며 회차 생성·검색·정렬·조회 실행은 FE가 한다. 모든 응답은 `Cache-Control: no-store`다. 워크스페이스별 쓰기 직렬화와 읽기 스냅샷 경계를 일치시켜 미커밋 변경이 증분 동기화에서 누락되지 않도록 커서를 발급한다.

### GET `/workspace` — 워크스페이스 전체 스냅샷

- **인증 요구사항**: ✅
- **설명**: 아이템·컬렉션·연결·반복 규칙·예외·충돌·설정·입력 구성·고정·시스템 알림·ICS 기준값을 통째로 반환한다. 소프트 삭제 상태도 포함한다. 대화 본문과 울린 알람은 제외한다. 진행 중인 미커밋 쓰기가 없는 일관된 스냅샷 경계에서 조회하고 해당 시점의 커서를 반환하여, 이후 변경을 빠짐없이 이어 받을 수 있게 보장한다. 요청 본문은 없습니다.

**Response 200 OK**

| 필드          | 타입            | 설명                                          |
| ------------- | --------------- | --------------------------------------------- |
| `success`     | `Boolean`       | `true`                                        |
| `data`        | `WorkspaceDto`  | 공통 DTO 참조                                 |
| `data.cursor` | `String`        | 이 스냅샷에 대응하는 불투명 증분 커서         |
| `data.items`  | `List<ItemDto>` | 마스터와 저장된 예외 아이템. 계산 회차는 없음 |
| `error`       | `null`          | 오류 없음                                     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **403 Forbidden**: 탭·세션 계정 불일치 (`ACCOUNT_MISMATCH`)

---

### GET `/workspace/changes` — 커서 이후 변경 받기

- **인증 요구사항**: ✅
- **설명**: 레코드 추가·수정과 연결 해제(`LINK`)·고정 해제(`PIN`)·물리 삭제·대화 기록 삭제(`CONVERSATION`)의 tombstone을 반환한다. 대화 기록의 실제 본문은 싣지 않는다. 입력 화면 구성(`INPUT_SCREEN_CONFIG`)은 증분 대상이 아니며 스냅샷과 `GET /input-screen-configs`로 받는다. 해당 워크스페이스의 미커밋 변경 구멍이 없는 경계에서 조회하며, 페이지 마지막 항목 기준으로 `nextCursor`를 전진시킨다. 보존 기한(35일)이 지난 커서는 `409 SYNC_CURSOR_EXPIRED`로 응답하여 전체 스냅샷을 다시 받게 한다. 요청 본문은 없습니다.

**Query Parameters**

| 파라미터 | 타입      | 필수 | 기본값 | 설명                         |
| -------- | --------- | ---- | ------ | ---------------------------- |
| `cursor` | `String`  | ✅   | 없음   | 이전 스냅샷·변경 응답의 커서 |
| `limit`  | `Integer` | -    | `500`  | 변경 수 상한 (최대 `1000`)   |

**Response 200 OK**

| 필드              | 타입                       | 설명                                    |
| ----------------- | -------------------------- | --------------------------------------- |
| `success`         | `Boolean`                  | `true`                                  |
| `data`            | `WorkspaceChangesDto`      | 공통 DTO 참조                           |
| `data.changes`    | `List<WorkspaceChangeDto>` | 이 페이지의 확정된 변경                 |
| `data.nextCursor` | `String`                   | 이 페이지까지 적용한 뒤 보관            |
| `data.hasMore`    | `Boolean`                  | `true`면 같은 방식으로 다음 페이지 요청 |
| `error`           | `null`                     | 오류 없음                               |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **403 Forbidden**: 계정 불일치 (`ACCOUNT_MISMATCH`)
- **409 Conflict**: 커서를 더 이상 이어 받을 수 없음 (`SYNC_CURSOR_EXPIRED`)

---

## 3. 아이템·연결 (Item & Link)

> F-09·F-12~F-24·F-28·F-30·F-33·F-41·F-49. 단건 쓰기는 `writeId`로 재전송을 식별하며, 같은 ID에 다른 요청 본문이 오면 `409 IDEMPOTENCY_KEY_REUSED`로 거절한다. `fields`는 공통 `ItemFieldsDto`를 사용한다.

### POST `/items` — 아이템 하나 만들기

- **인증 요구사항**: ✅
- **설명**: 기기가 정한 `itemId`로 생성하고 기본 소속·필수 칸·값 범위를 검증한다. 한 아이템의 일정·할 일·노트 성격은 조합할 수 있다. 같은 생성 재전송은 한 번만 반영한다. 시간표 속성은 만들 때만 붙일 수 있다. 본문·언급 연결은 한 트랜잭션으로 저장한다.

**Request Body (`ItemCreateRequest`)**

| 필드      | 타입             | 필수 | Validation                    | 설명                            |
| --------- | ---------------- | ---- | ----------------------------- | ------------------------------- |
| `writeId` | `UUID`           | ✅   | 기기에서 생성, 재전송 시 고정 | 단건 쓰기 멱등 식별자           |
| `item`    | `ItemCreateSpec` | ✅   | 필수 칸·소유·시간표 겹침 검증 | 구성 코드가 필수 칸 검증을 보조 |

**Response 200 OK**

| 필드                    | 타입             | 설명                    |
| ----------------------- | ---------------- | ----------------------- |
| `success`               | `Boolean`        | `true`                  |
| `data`                  | `WriteResultDto` | 공통 DTO 참조           |
| `data.item`             | `ItemDto`        | 서버 정본               |
| `data.alreadyProcessed` | `Boolean`        | 같은 쓰기의 재전송 여부 |
| `error`                 | `null`           | 오류 없음               |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **400 Bad Request**: 필수 칸·시간·속성 조합 오류 (`WRITE_REJECTED_VALIDATION`)
- **409 Conflict**: 같은 컬렉션의 시간표 겹침 (`TIMETABLE_SLOT_OVERLAP`)
- **409 Conflict**: ID 재사용 내용 불일치 (`IDEMPOTENCY_KEY_REUSED`)

---

### GET `/items/{itemId}` — 저장된 아이템 정본 조회

- **인증 요구사항**: ✅
- **설명**: 아이템 하나와 칸별 마지막 쓰기·충돌 표시를 반환한다. 회차를 새로 계산해 주는 API가 아니다. 소프트 삭제 상태도 반환하여 FE가 되살리기를 안내할 수 있게 한다. 요청 본문은 없습니다.

**Path Variables**

| 변수     | 타입   | 설명                              |
| -------- | ------ | --------------------------------- |
| `itemId` | `UUID` | 마스터 또는 저장된 예외 아이템 ID |

**Response 200 OK**

| 필드      | 타입      | 설명          |
| --------- | --------- | ------------- |
| `success` | `Boolean` | `true`        |
| `data`    | `ItemDto` | 공통 DTO 참조 |
| `error`   | `null`    | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)

---

### PATCH `/items/{itemId}` — 칸 단위 수정·회차 예외·반복 전체 변경

- **인증 요구사항**: ✅
- **설명**: 보낸 칸만 합친다. 생략은 유지, 명시적 `null`은 허용되는 칸의 값 제거다. 일반 쓰기에서 같은 칸이 부딪치면 먼저 온 값을 유지하고 진 쓰기를 후보로 보존하며, 적용된 변경의 마지막 쓰기와 직전 값 이력을 추적한다. 일정의 시작과 끝은 칸 단위(`START_AT`·`END_AT`·`START_DATE`·`END_DATE`)로 각각 버전을 검사하고 충돌을 기록한다. 소프트 삭제된 대상에도 쓰기를 반영하되 자동 복원하지 않고 시스템 안내(`WRITE_ON_DELETED_ITEM`)를 만든다. `THIS_OCCURRENCE`는 `(masterItemId, recurrenceDate, slotId?)` 기준으로 손댄 칸만 예외로 저장한다. `THIS_AND_FUTURE`·`ALL`은 F-24·F-33의 나눔·예외 이동·사슬 전파 규칙을 따르며, 시간표 수정·나누기 시 기존 회차 예외가 조용히 유실되지 않도록 대응 `slotId`로 재연결하거나 짝이 사라진 경우 `ORPHANED_OCCURRENCE` 충돌 후보로 전환한다.

**Path Variables**

| 변수     | 타입   | 설명                            |
| -------- | ------ | ------------------------------- |
| `itemId` | `UUID` | 일반 아이템 또는 반복 마스터 ID |

**Request Body (`ItemPatchRequest`)**

| 필드                 | 타입                 | 필수 | Validation                                          | 설명                                                                 |
| -------------------- | -------------------- | ---- | --------------------------------------------------- | -------------------------------------------------------------------- |
| `writeId`            | `UUID`               | ✅   | 재전송 시 고정                                      | 쓰기를 만든 기기에서 생성                                            |
| `scope`              | `MutationScope`      | ✅   | 대상과 맞는 범위                                    | 단건·회차·이후 전체·전체                                             |
| `occurrence`         | `OccurrenceRefDto`   | -    | 회차 수정 시 필수                                   | 원래 회차의 식별값 (`masterItemId`, `recurrenceDate`, `slotId?`)     |
| `effectiveDate`      | `LocalDate`          | -    | 이후 전체일 때 필수                                 | 적용 시작일                                                          |
| `baseFieldVersions`  | `Map<String, UUID?>` | ✅   | 변경하는 각 칸의 기준 버전 포함                     | `null`은 그 칸에 이전 쓰기가 없었다는 뜻                             |
| `changedFields`      | `ItemFieldsDto`      | ✅   | 한 칸 이상. 연결 대상용 예외 생성 때는 빈 객체 허용 | 바뀐 칸만 포함                                                       |
| `referenceTargetIds` | `List<UUID>`         | -    | 본문 변경 시 필수, 빈 배열 허용                     | 본문과 함께 교체. 본문이 후보가 되면 언급 집합도 그 후보와 함께 보존 |
| `clientEditedAt`     | `Instant`            | -    | 정리 대기 본문 직접 수정 시 필수                    | 기기에서 본문을 고친 시각                                            |
| `clientTimeZone`     | `String`             | -    | 위 경우 필수, IANA 시간대                           | 정리 대기 기준 시간대 갱신                                           |
| `materializeForLink` | `Boolean`            | -    | `THIS_OCCURRENCE`에서만 허용                        | `true`이면 값 변경 없이 연결 대상용 예외 아이템 생성                 |

**Response 200 OK**

| 필드             | 타입                    | 설명                                       |
| ---------------- | ----------------------- | ------------------------------------------ |
| `success`        | `Boolean`               | `true`                                     |
| `data`           | `WriteResultDto`        | 공통 DTO 참조                              |
| `data.conflicts` | `List<ConflictDto>`     | 빈 배열이면 새 충돌 없음                   |
| `data.warnings`  | `List<WriteWarningDto>` | 삭제된 대상·보호된 사슬 값·짝 없는 예외 등 |
| `error`          | `null`                  | 오류 없음                                  |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **400 Bad Request**: 검증 실패 (`WRITE_REJECTED_VALIDATION`)
- **410 Gone**: 소유가 확인된 물리 삭제 대상 (`WRITE_REJECTED_PURGED`)
- **409 Conflict**: 시간표 마스터 시간대 겹침 (`TIMETABLE_SLOT_OVERLAP`)
- **400 Bad Request**: 반복 경계 오류 (`INVALID_RECURRENCE_SPLIT_BOUNDARY`)

---

### POST `/items/trash` — 아이템 하나·여럿·반복 범위 소프트 삭제

- **인증 요구사항**: ✅
- **설명**: 단건과 대량 삭제(F-15·F-38)를 한 요청으로 받는다. 함께 지운 대상을 휴지통 묶음으로 만들고 원자적으로 반영한다. 반복 회차 하나의 삭제는 회차 예외(`isDeletedOccurrence`)로 저장하며 휴지통 줄을 만들지 않는다. 반복 전체 삭제는 나뉜 마스터와 예외를 포함한다. 출처로 만든 아이템은 명시적으로 선택한 경우만 함께 지운다.

**Request Body (`ItemsTrashRequest`)**

| 필드                      | 타입                    | 필수 | Validation                      | 설명                                                            |
| ------------------------- | ----------------------- | ---- | ------------------------------- | --------------------------------------------------------------- |
| `batchId`                 | `UUID`                  | ✅   | 재전송 시 고정                  | `BULK_DELETE` 묶음                                              |
| `targets`                 | `List<DeleteTargetDto>` | ✅   | 한 개 이상, 모든 대상 소유 확인 | 삭제 범위 포함                                                  |
| `confirmedSeriesDeletion` | `Boolean`               | -    | 반복 전체 삭제이면 `true`       | FE 확인 완료. 일반 단건·일괄 삭제에는 추가 확인을 요구하지 않음 |

**Response 200 OK**

| 필드      | 타입                  | 설명          |
| --------- | --------------------- | ------------- |
| `success` | `Boolean`             | `true`        |
| `data`    | `BatchWriteResultDto` | 공통 DTO 참조 |
| `error`   | `null`                | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **400 Bad Request**: 묶음 검증 실패·전체 미반영 (`BATCH_ATOMIC_WRITE_FAILED`)

---

## 4. 아이템 연결 (Link)

> F-16·F-22. 손으로 만들고 끊을 수 있는 연결은 `CREATED_FROM`뿐이다. `REFERENCE`는 본문 쓰기에, 나머지는 회차·미룸·나눔 연산에 묶어 저장한다. 연결 해제 시 다른 기기에도 증분 동기화 tombstone으로 전파한다. 관련 목록과 나뉨 사슬의 합치기는 FE가 한다.

### POST `/links` — 출처 연결 걸기

- **인증 요구사항**: ✅
- **설명**: 양쪽 소유를 확인하고 같은 `(sourceItemId, targetItemId, linkType)`는 하나만 둔다. 출처에서 파생 아이템으로 향하는 방향을 사용한다.

**Request Body (`LinkCreateRequest`)**

| 필드           | 타입       | 필수 | Validation            | 설명                           |
| -------------- | ---------- | ---- | --------------------- | ------------------------------ |
| `writeId`      | `UUID`     | ✅   | 재전송 시 고정        | 쓰기 식별자                    |
| `sourceItemId` | `UUID`     | ✅   | 내 아이템             | 출처                           |
| `targetItemId` | `UUID`     | ✅   | 내 아이템             | 출처로부터 만들어진 아이템     |
| `linkType`     | `LinkType` | ✅   | `CREATED_FROM`만 허용 | 다른 종류는 전용 동작으로 생성 |

**Response 200 OK**

| 필드      | 타입      | 설명          |
| --------- | --------- | ------------- |
| `success` | `Boolean` | `true`        |
| `data`    | `LinkDto` | 공통 DTO 참조 |
| `error`   | `null`    | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **400 Bad Request**: 허용되지 않은 연결 종류 (`WRITE_REJECTED_VALIDATION`)

---

### DELETE `/links/{linkId}` — 출처 연결 끊기

- **인증 요구사항**: ✅
- **설명**: 출처 연결만 제거하고 증분 동기화에서 `LINK` 삭제 tombstone이 전파되도록 처리한다. 양쪽 아이템은 지우지 않는다. 요청 본문은 없습니다.

**Path Variables**

| 변수     | 타입   | 설명                      |
| -------- | ------ | ------------------------- |
| `linkId` | `UUID` | 내 `CREATED_FROM` 연결 ID |

**Query Parameters**

| 파라미터  | 타입   | 필수 | 기본값 | 설명                       |
| --------- | ------ | ---- | ------ | -------------------------- |
| `writeId` | `UUID` | ✅   | 없음   | 재전송 때 유지하는 쓰기 ID |

**Response 200 OK**

| 필드      | 타입                 | 설명          |
| --------- | -------------------- | ------------- |
| `success` | `Boolean`            | `true`        |
| `data`    | `MutationReceiptDto` | 공통 DTO 참조 |
| `error`   | `null`               | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **400 Bad Request**: 수동 해제가 불가능한 연결 (`WRITE_REJECTED_VALIDATION`)

---

## 5. 컬렉션 (Collection)

> F-37·F-38·F-40. 아이템은 컬렉션 하나에 속한다. 기본 컬렉션은 가입할 때 하나 만들며 삭제할 수 없다. 여러 아이템 옮기기는 각각 `/items/{itemId}`의 소속 칸 쓰기다.

### POST `/collections` — 컬렉션 만들기

- **인증 요구사항**: ✅
- **설명**: 종류 없는 컬렉션을 만든다. 이름·색·아이콘은 FE가 채워 보낸다. Figma의 초기 색·아이콘은 미정이며 API가 임의 기본값을 확정하지 않는다.

**Request Body (`CollectionCreateRequest`)**

| 필드           | 타입          | 필수 | Validation                      | 설명                    |
| -------------- | ------------- | ---- | ------------------------------- | ----------------------- |
| `writeId`      | `UUID`        | ✅   | 재전송 시 고정                  | 쓰기 식별자             |
| `collectionId` | `UUID`        | ✅   | 기기에서 생성                   | 오프라인 생성 지원      |
| `name`         | `String`      | ✅   | 공백만 불가                     | 빈 이름 금지            |
| `color`        | `SwatchColor` | ✅   | 정의된 스와치                   | 컬렉션 색은 `null` 불가 |
| `icon`         | `String`      | ✅   | 등록된 아이콘 식별자, 최대 50자 | 아이콘 식별자           |

**Response 200 OK**

| 필드      | 타입            | 설명          |
| --------- | --------------- | ------------- |
| `success` | `Boolean`       | `true`        |
| `data`    | `CollectionDto` | 공통 DTO 참조 |
| `error`   | `null`          | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **400 Bad Request**: 이름·색·아이콘 검증 오류 (`WRITE_REJECTED_VALIDATION`)

---

### PATCH `/collections/{collectionId}` — 컬렉션 이름·색·아이콘 수정

- **인증 요구사항**: ✅
- **설명**: 보낸 칸만 수정한다. 이름은 암호화하며 이름순 정렬은 FE가 한다. 아이템 소속과 시간표 기간은 바꾸지 않는다. `baseSyncSeq`가 현재 서버 버전과 다르면 조용히 덮지 않고 `409 RESOURCE_VERSION_CHANGED`로 거절한다.

**Path Variables**

| 변수           | 타입   | 설명         |
| -------------- | ------ | ------------ |
| `collectionId` | `UUID` | 내 컬렉션 ID |

**Request Body (`CollectionPatchRequest`)**

| 필드          | 타입          | 필수 | Validation           | 설명                              |
| ------------- | ------------- | ---- | -------------------- | --------------------------------- |
| `writeId`     | `UUID`        | ✅   | 재전송 시 고정       | 쓰기 식별자                       |
| `baseSyncSeq` | `Int64`       | ✅   | 조회한 컬렉션의 버전 | 동시 변경 시 덮지 않고 `409` 거절 |
| `name`        | `String`      | -    | 공백만 불가          | 이름 변경                         |
| `color`       | `SwatchColor` | -    | `null` 불가          | 색 변경                           |
| `icon`        | `String`      | -    | 최대 50자            | 아이콘 변경                       |

**Response 200 OK**

| 필드      | 타입            | 설명          |
| --------- | --------------- | ------------- |
| `success` | `Boolean`       | `true`        |
| `data`    | `CollectionDto` | 공통 DTO 참조 |
| `error`   | `null`          | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **409 Conflict**: 기준 버전이 달라짐 (`RESOURCE_VERSION_CHANGED`)

---

### POST `/collections/{collectionId}/trash` — 컬렉션과 소속 아이템 함께 삭제

- **인증 요구사항**: ✅
- **설명**: 컬렉션과 그 안의 아이템을 한 휴지통 묶음으로 소프트 삭제한다. 함께 복구할 수 있도록 원자적으로 처리한다.

**Path Variables**

| 변수           | 타입   | 설명         |
| -------------- | ------ | ------------ |
| `collectionId` | `UUID` | 내 컬렉션 ID |

**Request Body (`CollectionTrashRequest`)**

| 필드        | 타입      | 필수 | Validation     | 설명                  |
| ----------- | --------- | ---- | -------------- | --------------------- |
| `writeId`   | `UUID`    | ✅   | 재전송 시 고정 | 컬렉션 삭제 쓰기 ID   |
| `confirmed` | `Boolean` | ✅   | `true`         | 컬렉션 삭제 확인 완료 |

**Response 200 OK**

| 필드      | 타입             | 설명          |
| --------- | ---------------- | ------------- |
| `success` | `Boolean`        | `true`        |
| `data`    | `TrashBundleDto` | 공통 DTO 참조 |
| `error`   | `null`           | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **400 Bad Request**: 기본 컬렉션 삭제 (`DEFAULT_COLLECTION_CANNOT_DELETE`)

---

## 6. 휴지통 (Trash)

> F-11·F-15. 보관 30일은 서버가 소프트 삭제를 반영한 때부터 센다. 휴지통 비우기·즉시 영구 삭제 API는 제공하지 않는다.

### GET `/trash` — 휴지통 묶음 목록

- **인증 요구사항**: ✅
- **설명**: 삭제 시각 역순으로 반복 마스터 묶음·컬렉션 묶음·일괄 삭제 묶음을 반환한다. 요청 본문은 없습니다.

**Query Parameters**

| 파라미터 | 타입      | 필수 | 기본값 | 설명                      |
| -------- | --------- | ---- | ------ | ------------------------- |
| `cursor` | `String`  | -    | 없음   | 다음 페이지용 불투명 커서 |
| `limit`  | `Integer` | -    | `50`   | 페이지 크기 (최대 `200`)  |

**Response 200 OK**

| 필드      | 타입                                 | 설명          |
| --------- | ------------------------------------ | ------------- |
| `success` | `Boolean`                            | `true`        |
| `data`    | `CursorPageResponse<TrashBundleDto>` | 공통 DTO 참조 |
| `error`   | `null`                               | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

### POST `/trash/{trashBundleId}/restore` — 함께 지운 대상 복구

- **인증 요구사항**: ✅
- **설명**: 묶음에 속해 함께 지워진 대상을 함께 복구한다. 관련 목록과 위키링크도 복구된 상태를 따라 살아난다. 컬렉션·시간표 겹침 검증이 실패하면 부분 복구하지 않고 `409 TIMETABLE_SLOT_OVERLAP`으로 거절한다.

**Path Variables**

| 변수            | 타입   | 설명           |
| --------------- | ------ | -------------- |
| `trashBundleId` | `UUID` | 휴지통 묶음 ID |

**Request Body (`TrashRestoreRequest`)**

| 필드      | 타입   | 필수 | Validation     | 설명             |
| --------- | ------ | ---- | -------------- | ---------------- |
| `writeId` | `UUID` | ✅   | 재전송 시 고정 | 복구 쓰기 식별자 |

**Response 200 OK**

| 필드      | 타입               | 설명          |
| --------- | ------------------ | ------------- |
| `success` | `Boolean`          | `true`        |
| `data`    | `RestoreResultDto` | 공통 DTO 참조 |
| `error`   | `null`             | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **410 Gone**: 이미 물리 삭제됨 (`WRITE_REJECTED_PURGED`)
- **409 Conflict**: 복구 시 시간표 겹침 (`TIMETABLE_SLOT_OVERLAP`)

---

## 7. 원자 묶음 쓰기 (Atomic Batch)

> F-18·F-31·F-34·F-42·F-43·F-47·F-49. 한 문장의 만들기·고치기·지우기·회차 예외·반복 나누기는 단일 트랜잭션 안에서 모두 성공하거나 모두 미반영이어야 한다.

### POST `/batches` — 한 묶음 원자 저장

- **인증 요구사항**: ✅
- **설명**: `batchId`를 사용자에 묶어 한 번만 적용하고 같은 요청은 저장된 결과를 돌려준다. 하나라도 검증·버전·권한 검사에 실패하면 아이템·연결·반복·컬렉션 변경을 전부 롤백한다. 단건 일반 쓰기와 달리 원자 묶음(`AI_MUTATION`, `ICS_IMPORT` 등)에서 `baseFieldVersions`가 현재 서버 버전과 다르면 충돌 후보를 반쪽 생성하지 않고 `409 BATCH_CONFLICT`로 묶음 전체를 거절하여 FE가 최신 값을 보고 다시 확인하게 한다. AI 요청은 `aiRequestId`의 완료 기록과 같은 트랜잭션에서 결합하여, 이후 결과를 되돌리더라도 다른 기기나 지연 요청이 새 묶음 ID로 같은 AI 요청을 다시 실행·저장하지 못하게 차단한다.

**Request Body (`BatchWriteRequest`)**

| 필드                     | 타입                        | 필수 | Validation                              | 설명                                                  |
| ------------------------ | --------------------------- | ---- | --------------------------------------- | ----------------------------------------------------- |
| `batchId`                | `UUID`                      | ✅   | 같은 저장 재시도에는 같은 값            | 다른 사용자의 묶음 기록은 노출하지 않음               |
| `batchType`              | `BatchType`                 | ✅   | 정의된 종류                             | 임의 일괄 편집은 허용하지 않음                        |
| `aiRequestId`            | `UUID`                      | -    | AI 저장 시 필수                         | 되묻기와 단일 저장 연결                               |
| `expectedAiRevision`     | `String`                    | -    | AI 저장 시 필수                         | FE가 검증한 해석 버전                                 |
| `newCollection`          | `CollectionCreateSpec`      | -    | 시간표 만들기(`TIMETABLE_CREATE`)에서만 | 새 컬렉션을 시간표 아이템과 함께 원자 저장            |
| `creates`                | `List<ItemCreateSpec>`      | ✅   | 없으면 `[]`                             | 기기 ID로 생성                                        |
| `patches`                | `List<ItemPatchSpec>`       | ✅   | 없으면 `[]`                             | 범위·기준 칸 버전 포함                                |
| `deletes`                | `List<DeleteTargetDto>`     | ✅   | 없으면 `[]`                             | 회차·반복 범위 포함                                   |
| `linksToUpsert`          | `List<LinkSpec>`            | ✅   | 없으면 `[]`                             | 출처 등 해당 동작이 허용한 연결                       |
| `postpone`               | `PostponeSpec`              | -    | `TODO_POSTPONE`에서 필수                | 원본 상태·이월본·미룸 연결을 함께 처리                |
| `pendingInboxCompletion` | `PendingInboxCompletionDto` | -    | 정리 대기 재시도 완료 시                | 본문 밖 속성이 있으면 표시만 제거, 아니면 휴지통 이동 |

**Response 200 OK**

| 필드                    | 타입                      | 설명                                      |
| ----------------------- | ------------------------- | ----------------------------------------- |
| `success`               | `Boolean`                 | `true`                                    |
| `data`                  | `BatchWriteResultDto`     | 공통 DTO 참조                             |
| `data.batchId`          | `UUID`                    | 실제로 확정된 묶음 ID                     |
| `data.alreadyProcessed` | `Boolean`                 | 같은 묶음 또는 같은 AI 요청이 먼저 완료됨 |
| `data.results`          | `List<MutationResultDto>` | 각 결과와 되돌리기 식별자                 |
| `error`                 | `null`                    | 오류 없음                                 |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **400 Bad Request**: 하나 이상의 검증 실패·전체 미반영 (`BATCH_ATOMIC_WRITE_FAILED`)
- **409 Conflict**: 시간표 겹침 (`TIMETABLE_SLOT_OVERLAP`)
- **409 Conflict**: 기준 칸 버전이 달라져 전체 롤백됨 (`BATCH_CONFLICT`)
- **409 Conflict**: 같은 ID를 다른 요청에 재사용 (`IDEMPOTENCY_KEY_REUSED`)

---

### GET `/batches/{batchId}` — 기존 묶음 저장 상태 확인

- **인증 요구사항**: ✅
- **설명**: 저장 응답을 받지 못했을 때 기존 묶음의 확정 상태(`status`)와 당시 성공 결과(`result`)를 조회한다. 원자 묶음 실패는 트랜잭션 전체가 롤백되므로 별도 실패 상세 객체를 보장하지 않는다. `404`는 요청이 처리 중이거나 아직 기록되지 않았을 가능성이 있어 저장 실패의 증거가 아니다. 결과를 모르면 같은 묶음 ID로 재전송한다. 요청 본문은 없습니다.

**Path Variables**

| 변수      | 타입   | 설명                |
| --------- | ------ | ------------------- |
| `batchId` | `UUID` | 원래 요청의 묶음 ID |

**Response 200 OK**

| 필드          | 타입                   | 설명                            |
| ------------- | ---------------------- | ------------------------------- |
| `success`     | `Boolean`              | `true`                          |
| `data`        | `BatchStatusDto`       | 공통 DTO 참조                   |
| `data.status` | `BatchStatus`          | 기록된 확정 상태                |
| `data.result` | `BatchWriteResultDto?` | 성공 결과, 미반영 상태면 `null` |
| `error`       | `null`                 | 오류 없음                       |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)

---

## 8. 충돌 (Conflict)

> F-45. 일반 칸 충돌은 먼저 도착한 값과 진 후보를 모두 보존한다. 이름·본문·장소·태그의 같은 값 판별은 FE가 하며 서버는 해당 글의 의미·동등성을 비교하지 않는다.

### GET `/conflicts` — 풀지 않은 충돌 목록

- **인증 요구사항**: ✅
- **설명**: `(itemId, fieldKey)` 단위로 묶은 현재 서버 값과 보존된 모든 미해결 후보를 반환한다. 모든 기기에서 같은 충돌을 보며 해결 전까지 유지한다. 보존된 짝 없는 예외(`ORPHANED_OCCURRENCE`)도 포함한다. 요청 본문은 없습니다.

**Query Parameters**

| 파라미터 | 타입      | 필수 | 기본값 | 설명                     |
| -------- | --------- | ---- | ------ | ------------------------ |
| `cursor` | `String`  | -    | 없음   | 페이지 커서              |
| `limit`  | `Integer` | -    | `50`   | 페이지 크기 (최대 `200`) |

**Response 200 OK**

| 필드      | 타입                              | 설명          |
| --------- | --------------------------------- | ------------- |
| `success` | `Boolean`                         | `true`        |
| `data`    | `CursorPageResponse<ConflictDto>` | 공통 DTO 참조 |
| `error`   | `null`                            | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

### POST `/conflicts/resolve` — 후보 선택·태그 합집합·예외 맞추기

- **인증 요구사항**: ✅
- **설명**: 서버 값 유지, 후보 선택, FE가 만든 값, 짝 없는 예외의 재연결·버리기를 처리한다. 함께 부딪친 시작·끝은 FE가 한 쌍(`resolutions`에 시작 칸과 끝 칸 함께 포함)으로 보내고 서버가 함께 검증·반영한다. 본문 후보를 선택할 때는 그 후보에 묶인 `referenceTargetIds`로 언급 연결(`REFERENCE`)도 함께 맞춘다. 후보·서버 버전이 달라지면 이전 선택으로 덮지 않는다. 예외를 버리면 해당 예외를 소프트 삭제한다.

**Request Body (`ConflictResolveRequest`)**

| 필드          | 타입                          | 필수 | Validation     | 설명                        |
| ------------- | ----------------------------- | ---- | -------------- | --------------------------- |
| `writeId`     | `UUID`                        | ✅   | 재전송 시 고정 | 해결 쓰기 ID                |
| `resolutions` | `List<ConflictResolutionDto>` | ✅   | 한 개 이상     | 공통 버전 확인 후 원자 반영 |

**Response 200 OK**

| 필드      | 타입                          | 설명          |
| --------- | ----------------------------- | ------------- |
| `success` | `Boolean`                     | `true`        |
| `data`    | `ConflictResolutionResultDto` | 공통 DTO 참조 |
| `error`   | `null`                        | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **409 Conflict**: 후보·서버 버전 변경 (`CONFLICT_CHANGED`)
- **400 Bad Request**: 잘못된 선택·재연결 값 (`WRITE_REJECTED_VALIDATION`)

---

## 9. 되돌리기 (Undo)

> F-11·F-18·F-48. 값이 우연히 같다는 이유로 되돌리기를 허용하지 않는다. 칸별 쓰기 계보를 검사하여 뒤 요청부터 정상 취소한 경우에만 앞 요청을 이어 취소한다. 대화 기록이 삭제된 후에는 그 기록에 속한 결과의 되돌리기를 허용하지 않는다.

### POST `/undo` — 선택한 결과 하나 되돌리기

- **인증 요구사항**: ✅
- **설명**: 만든 것은 휴지통으로, 지운 것은 복구, 고친 것은 해당 요청이 바꾼 칸만 직전 값으로 되돌린다. 회차에서 그 요청으로 처음 생긴 예외 값은 제거하여 마스터 상속 상태로 돌리고, 원래 있던 예외 값은 요청 직전 예외 값으로 복원한다. 미룸은 이월본이 미완료인 경우만 허용하고 다시 미룬 것은 끝부터 처리한다. 생성 뒤 수정된 아이템을 휴지통으로 보낼 때는 현재 버전을 보인 뒤 확인을 받는다. 대화 기록 삭제 후 해당 결과의 취소 요청이나 반복 나누기 결과의 되돌리기(명세 미정)는 `409 UNDO_NOT_SUPPORTED`로 거절한다.

**Request Body (`UndoRequest`)**

| 필드                          | 타입                 | 필수 | Validation                   | 설명                         |
| ----------------------------- | -------------------- | ---- | ---------------------------- | ---------------------------- |
| `writeId`                     | `UUID`               | ✅   | 재전송 시 고정               | 취소 쓰기 ID                 |
| `sourceWriteId`               | `UUID`               | -    | 단건 취소에서 필수           | 원래 쓰기 ID                 |
| `batchId`                     | `UUID`               | -    | 묶음의 결과 취소에서 필수    | 원래 묶음                    |
| `resultId`                    | `UUID`               | -    | 위 경우 필수                 | 묶음 전체가 아닌 선택한 결과 |
| `baseFieldVersions`           | `Map<String, UUID?>` | ✅   | 현재 대상 버전               | 이후 변경 검사               |
| `confirmDeleteEditedCreation` | `Boolean`            | -    | 수정된 생성물 삭제 시 `true` | 확인 이후 다시 바뀌면 거절   |

**Response 200 OK**

| 필드      | 타입            | 설명          |
| --------- | --------------- | ------------- |
| `success` | `Boolean`       | `true`        |
| `data`    | `UndoResultDto` | 공통 DTO 참조 |
| `error`   | `null`          | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **409 Conflict**: 이후 변경이 남음 (`UNDO_BLOCKED_BY_LATER_CHANGE`)
- **409 Conflict**: 이월본 상태·순서가 맞지 않음 (`POSTPONE_UNDO_NOT_ALLOWED`)
- **409 Conflict**: 되돌리기 미지원 연산 (`UNDO_NOT_SUPPORTED`)

---

## 10. 설정·보기 상태·고정·입력 구성 (Settings & View)

> F-07·F-09·F-26·F-37·F-46·F-51·F-52. 사용자 설정·보기 상태는 서버에 저장한다. 검색어·OS 권한·웹 아이디 저장은 전송하지 않는다. 아래 읽기 API는 워크스페이스를 다시 받지 않고 갱신할 때 사용한다.

### GET `/settings` — 사용자 설정 조회

- **인증 요구사항**: ✅
- **설명**: AI 지침·동의·캘린더(기본 시간대·기본 알림·공휴일 표시)·외형·단축키 설정을 반환한다. 요청 본문은 없습니다.

**Response 200 OK**

| 필드      | 타입          | 설명          |
| --------- | ------------- | ------------- |
| `success` | `Boolean`     | `true`        |
| `data`    | `SettingsDto` | 공통 DTO 참조 |
| `error`   | `null`        | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

### PATCH `/settings` — 사용자 설정 일부 변경

- **인증 요구사항**: ✅
- **설명**: 보낸 설정만 바꾼다. AI·서버 OCR 동의를 끄면 이후 해당 호출을 거절한다. 첫 출하의 밀도는 고정이므로 변경 요청을 허용하지 않는다. 동의 철회가 기존 대화 기록을 자동 삭제하지는 않는다. `baseSyncSeq`가 현재 서버 버전과 다르면 조용히 덮지 않고 `409 RESOURCE_VERSION_CHANGED`로 거절한다.

**Request Body (`SettingsPatchRequest`)**

| 필드          | 타입               | 필수 | Validation     | 설명                              |
| ------------- | ------------------ | ---- | -------------- | --------------------------------- |
| `writeId`     | `UUID`             | ✅   | 재전송 시 고정 | 쓰기 식별자                       |
| `baseSyncSeq` | `Int64`            | ✅   | 현재 설정 버전 | 동시 수정 시 덮지 않고 `409` 거절 |
| `changes`     | `SettingsPatchDto` | ✅   | 한 칸 이상     | 생략은 유지                       |

**Response 200 OK**

| 필드      | 타입          | 설명          |
| --------- | ------------- | ------------- |
| `success` | `Boolean`     | `true`        |
| `data`    | `SettingsDto` | 공통 DTO 참조 |
| `error`   | `null`        | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **400 Bad Request**: 하루 시작 시각·밀도 등 검증 오류 (`WRITE_REJECTED_VALIDATION`)
- **409 Conflict**: 설정 버전 변경 (`RESOURCE_VERSION_CHANGED`)

---

### GET `/view-states` — 모듈별 보기 상태 조회

- **인증 요구사항**: ✅
- **설명**: 정렬·그룹·필터·표시 모드·패널·표시 요일·끈 컬렉션 설정을 반환한다. 실제 정렬·필터링은 FE가 한다. 요청 본문은 없습니다.

**Response 200 OK**

| 필드      | 타입                 | 설명          |
| --------- | -------------------- | ------------- |
| `success` | `Boolean`            | `true`        |
| `data`    | `List<ViewStateDto>` | 공통 DTO 참조 |
| `error`   | `null`               | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

### PUT `/view-states/{viewKey}` — 보기 상태 저장

- **인증 요구사항**: ✅
- **설명**: 해당 보기 상태를 교체한다. 알려진 구성 스키마에 맞춰 검증한다. 마지막 모듈을 일반 실행의 첫 화면으로 기억시키는 용도로 쓰지 않는다.

**Path Variables**

| 변수      | 타입     | 설명                               |
| --------- | -------- | ---------------------------------- |
| `viewKey` | `String` | 보기 식별자. Enum이 아닌 등록 코드 |

**Request Body (`ViewStatePutRequest`)**

| 필드          | 타입                  | 필수 | Validation         | 설명                               |
| ------------- | --------------------- | ---- | ------------------ | ---------------------------------- |
| `writeId`     | `UUID`                | ✅   | 재전송 시 고정     | 쓰기 식별자                        |
| `baseSyncSeq` | `Int64?`              | -    | 없던 상태면 `null` | 동시 수정 시 `409` 거절            |
| `state`       | `ViewStatePayloadDto` | ✅   | 해당 보기의 필드만 | 검색어·사용자 본문은 저장하지 않음 |

**Response 200 OK**

| 필드      | 타입           | 설명          |
| --------- | -------------- | ------------- |
| `success` | `Boolean`      | `true`        |
| `data`    | `ViewStateDto` | 공통 DTO 참조 |
| `error`   | `null`         | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **400 Bad Request**: 등록되지 않은 보기·필드 (`WRITE_REJECTED_VALIDATION`)
- **409 Conflict**: 보기 버전 변경 (`RESOURCE_VERSION_CHANGED`)

---

### PUT `/pins/{itemId}` — 아이템 고정

- **인증 요구사항**: ✅
- **설명**: 고정을 아이템 속성과 분리된 사용자 보기 상태로 저장한다. 같은 고정은 하나만 둔다.

**Path Variables**

| 변수     | 타입   | 설명         |
| -------- | ------ | ------------ |
| `itemId` | `UUID` | 내 아이템 ID |

**Request Body (`PinPutRequest`)**

| 필드      | 타입   | 필수 | Validation     | 설명    |
| --------- | ------ | ---- | -------------- | ------- |
| `writeId` | `UUID` | ✅   | 재전송 시 고정 | 쓰기 ID |

**Response 200 OK**

| 필드      | 타입      | 설명          |
| --------- | --------- | ------------- |
| `success` | `Boolean` | `true`        |
| `data`    | `PinDto`  | 공통 DTO 참조 |
| `error`   | `null`    | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)

---

### DELETE `/pins/{itemId}` — 고정 해제

- **인증 요구사항**: ✅
- **설명**: 고정만 해제하고 증분 동기화에서 `PIN` 해제 tombstone이 전파되도록 처리하며 아이템은 유지한다. FE는 되돌리기를 제공한다. 요청 본문은 없습니다.

**Path Variables**

| 변수     | 타입   | 설명         |
| -------- | ------ | ------------ |
| `itemId` | `UUID` | 내 아이템 ID |

**Query Parameters**

| 파라미터  | 타입   | 필수 | 기본값 | 설명          |
| --------- | ------ | ---- | ------ | ------------- |
| `writeId` | `UUID` | ✅   | 없음   | 재전송 식별자 |

**Response 200 OK**

| 필드      | 타입                 | 설명          |
| --------- | -------------------- | ------------- |
| `success` | `Boolean`            | `true`        |
| `data`    | `MutationReceiptDto` | 공통 DTO 참조 |
| `error`   | `null`               | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)

---

### GET `/input-screen-configs` — 입력 화면 구성 조회

- **인증 요구사항**: ✅
- **설명**: 시드 작업이 넣은 입력 종류·순서·그룹·필수 칸을 반환한다. 한 화면으로 렌더링하는 일은 FE가 맡는다. 사용자 구성 편집·시드 실행은 공개 API로 제공하지 않는다. 요청 본문은 없습니다.

**Response 200 OK**

| 필드      | 타입                         | 설명          |
| --------- | ---------------------------- | ------------- |
| `success` | `Boolean`                    | `true`        |
| `data`    | `List<InputScreenConfigDto>` | 공통 DTO 참조 |
| `error`   | `null`                       | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

## 11. 문장 AI 해석·되묻기 (AI Interpretation)

> F-08·F-43·F-47·F-49. 해석 호출은 아이템 저장이 아니다. 명확한 요청은 FE 검증 뒤 바로 `/batches`로 저장하고, 의도·대상·범위가 모호할 때만 묻는다. 조회는 조건만 받아 FE에서 실행한다.

### POST `/ai/interpretations` — 문장 해석·정리 대기 재시도

- **인증 요구사항**: ✅
- **선행 조건**: AI 해석 동의 `aiConsent=true`
- **설명**: 문장·고정된 기준 시각/시간대·최소 선택 맥락·허용된 후보 칸·지침만 외부 AI로 보낸다. 전체 워크스페이스·화면·조회 결과 목록은 보내지 않는다. 결과 구조를 검사하고 후보/맥락 밖의 수정 대상은 허용하지 않는다. 되묻기(`ASK`)가 발생하면 대기 요청을 암호화 저장하여 다른 기기에서도 이어 답할 수 있게 한다. 정리 대기 재시도는 사용자 조작으로만 호출하며 현재 본문과 저장된 기준 시각/시간대를 사용한다. 문장을 해석하지 못하면 FE가 F-49의 정리 대기 아이템을 보존한다.

**Request Body (`InterpretationRequest`)**

| 필드                | 타입                   | 필수 | Validation               | 설명                                                    |
| ------------------- | ---------------------- | ---- | ------------------------ | ------------------------------------------------------- |
| `requestId`         | `UUID`                 | ✅   | 한 요청에서 고정         | 이미 완료된 요청이면 기존 결과 반환                     |
| `sentence`          | `String`               | ✅   | 빈 문장 불가             | 정리 대기 재시도에서는 현재 본문                        |
| `referenceTime`     | `Instant`              | ✅   | UTC                      | 상대 날짜 기준. 재시도 버튼을 누른 시각으로 바꾸지 않음 |
| `referenceTimeZone` | `String`               | ✅   | IANA 시간대              | 예: `Asia/Seoul`                                        |
| `context`           | `AiContextDto`         | ✅   | 보낸 순간의 맥락         | 나중에 화면이 바뀌어도 유지                             |
| `candidates`        | `List<AiCandidateDto>` | ✅   | 없으면 `[]`              | 기기가 선택한 최소 후보                                 |
| `guideline`         | `String`               | ✅   | 비어 있으면 `""`         | 해석 습관·기본값만 보조                                 |
| `pendingItemId`     | `UUID`                 | -    | 내 활성 정리 대기 아이템 | 있으면 본문·기준 시각 버전도 검증                       |
| `baseFieldVersions` | `Map<String, UUID?>`   | -    | 정리 대기 재시도 시 필수 | 본문이 그사이 바뀌면 다시 확인                          |

**Response 200 OK**

| 필드                    | 타입                  | 설명                                         |
| ----------------------- | --------------------- | -------------------------------------------- |
| `success`               | `Boolean`             | `true`                                       |
| `data`                  | `AiInterpretationDto` | 공통 DTO 참조                                |
| `data.kind`             | `AiResultKind`        | 실행 계획·질문·조회 조건·지원 불가·기존 완료 |
| `data.revision`         | `String`              | 이 결과를 검증한 후 묶음에 포함              |
| `data.committedBatchId` | `UUID?`               | 다른 기기가 이미 저장했을 때                 |
| `error`                 | `null`                | 오류 없음                                    |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **403 Forbidden**: 동의 없음 (`AI_CONSENT_REQUIRED`)
- **429 Too Many Requests**: 사용자 호출 한도 (`AI_RATE_LIMITED`)
- **502 Bad Gateway**: 상한 초과·구조화 출력 실패 (`AI_TIMEOUT_OR_INVALID_SCHEMA`)
- **409 Conflict**: 오래된 정리 대기 본문 (`RESOURCE_VERSION_CHANGED`)

---

### GET `/ai/requests/{requestId}` — 대기 질문·해석 결과·선저장 결과 조회

- **인증 요구사항**: ✅
- **설명**: 다른 기기에서 이어 답할 문장·초기 맥락·질문·모은 답·해석 버전·이미 저장된 묶음을 반환한다. 아이템 현재 상태는 워크스페이스 정본으로 다시 검사한다. 요청 본문은 없습니다.

**Path Variables**

| 변수        | 타입   | 설명            |
| ----------- | ------ | --------------- |
| `requestId` | `UUID` | 내 해석 요청 ID |

**Response 200 OK**

| 필드      | 타입           | 설명          |
| --------- | -------------- | ------------- |
| `success` | `Boolean`      | `true`        |
| `data`    | `AiRequestDto` | 공통 DTO 참조 |
| `error`   | `null`         | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)

---

### POST `/ai/requests/{requestId}/answers` — 되묻기에 이어 답하기

- **인증 요구사항**: ✅
- **선행 조건**: AI 동의 유지, 해당 요청이 답을 기다림
- **설명**: 선택지 또는 자유 입력 답을 처음 요청의 맥락·기준 시각·시간대와 함께 다시 해석한다. 답이 모이기 전에는 다른 결과도 저장하지 않는다. 늦은 답으로 날짜가 지났거나 대상이 바뀌었으면 다시 묻는다. 다른 기기가 먼저 저장했으면 새로 저장하지 않고 기존 묶음을 반환한다.

**Path Variables**

| 변수        | 타입   | 설명            |
| ----------- | ------ | --------------- |
| `requestId` | `UUID` | 내 대기 요청 ID |

**Request Body (`AiAnswerRequest`)**

| 필드               | 타입                | 필수 | Validation       | 설명                       |
| ------------------ | ------------------- | ---- | ---------------- | -------------------------- |
| `answerId`         | `UUID`              | ✅   | 재전송 시 고정   | 답 중복 적용 방지          |
| `expectedRevision` | `String`            | ✅   | 조회한 요청 버전 | 다른 기기의 답과 경합 검사 |
| `answers`          | `List<AiAnswerDto>` | ✅   | 하나 이상        | 선택지 또는 자유 글        |

**Response 200 OK**

| 필드      | 타입                  | 설명          |
| --------- | --------------------- | ------------- |
| `success` | `Boolean`             | `true`        |
| `data`    | `AiInterpretationDto` | 공통 DTO 참조 |
| `error`   | `null`                | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **403 Forbidden**: 동의 철회 (`AI_CONSENT_REQUIRED`)
- **409 Conflict**: 다른 답으로 요청 버전 변경 (`AI_REQUEST_CHANGED`)
- **429 Too Many Requests**: 한도 (`AI_RATE_LIMITED`)
- **502 Bad Gateway**: 재해석 실패 (`AI_TIMEOUT_OR_INVALID_SCHEMA`)

---

## 12. 대화 기록 (Conversation)

> F-47·F-48·F-50. 대화 기록은 사용자별 키로 암호화한다. 워크스페이스 초기 응답에는 넣지 않고 최근 순으로 나눠 받는다. 조회로 찾은 목록·아이템 값은 기록 API에도 보내지 않는다.

### GET `/conversations` — 최근 대화 기록 목록

- **인증 요구사항**: ✅
- **설명**: 암호화된 기록을 권한 확인 후 복호화해 반환한다. 날짜 묶음과 현재 아이템 상태 표시 계산은 FE가 한다. 요청 본문은 없습니다.

**Query Parameters**

| 파라미터 | 타입      | 필수 | 기본값 | 설명                     |
| -------- | --------- | ---- | ------ | ------------------------ |
| `cursor` | `String`  | -    | 없음   | 최근순 페이지 커서       |
| `limit`  | `Integer` | -    | `30`   | 페이지 크기 (최대 `100`) |

**Response 200 OK**

| 필드      | 타입                                         | 설명          |
| --------- | -------------------------------------------- | ------------- |
| `success` | `Boolean`                                    | `true`        |
| `data`    | `CursorPageResponse<ConversationSummaryDto>` | 공통 DTO 참조 |
| `error`   | `null`                                       | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

### PUT `/conversations/{conversationId}` — 문장·질문·답·결과 요약 기록

- **인증 요구사항**: ✅
- **설명**: 기기가 정한 ID의 기록을 생성하거나 같은 기록을 갱신한다. `SAVED`의 묶음 참조는 서버의 실제 저장 결과와 맞아야 한다. `QUERY_RESULT`는 문장·조회 조건만 보관하고 찾은 목록은 재열기 때 FE가 다시 실행한다. 질문 대기의 정본은 해석 요청(`requestId`)과 연결한다. 이미 삭제된 `conversationId`로의 늦은 쓰기는 `409 RESOURCE_VERSION_CHANGED`로 거절하여 삭제된 기록이 되살아나지 않게 한다.

**Path Variables**

| 변수             | 타입   | 설명                |
| ---------------- | ------ | ------------------- |
| `conversationId` | `UUID` | 기기가 정한 기록 ID |

**Request Body (`ConversationPutRequest`)**

| 필드               | 타입                    | 필수 | Validation                 | 설명                     |
| ------------------ | ----------------------- | ---- | -------------------------- | ------------------------ |
| `writeId`          | `UUID`                  | ✅   | 재전송 시 고정             | 기록 쓰기 ID             |
| `expectedRevision` | `String?`               | -    | 처음이면 `null`            | 동시 갱신·삭제 보호      |
| `record`           | `ConversationRecordDto` | ✅   | 문장·조건·서버 저장 참조만 | 조회 결과·화면 전체 금지 |

**Response 200 OK**

| 필드      | 타입              | 설명          |
| --------- | ----------------- | ------------- |
| `success` | `Boolean`         | `true`        |
| `data`    | `ConversationDto` | 공통 DTO 참조 |
| `error`   | `null`            | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **409 Conflict**: 다른 기기의 수정·삭제 (`RESOURCE_VERSION_CHANGED`)
- **400 Bad Request**: 허용되지 않은 기록 값 (`WRITE_REJECTED_VALIDATION`)

---

### GET `/conversations/{conversationId}` — 대화 기록 상세

- **인증 요구사항**: ✅
- **설명**: 당시 문장·질문·답·저장 결과 참조를 반환한다. 당시 결과와 지금 아이템 상태는 FE가 나눠 보여 준다. 요청 본문은 없습니다.

**Path Variables**

| 변수             | 타입   | 설명            |
| ---------------- | ------ | --------------- |
| `conversationId` | `UUID` | 내 대화 기록 ID |

**Response 200 OK**

| 필드      | 타입              | 설명          |
| --------- | ----------------- | ------------- |
| `success` | `Boolean`         | `true`        |
| `data`    | `ConversationDto` | 공통 DTO 참조 |
| `error`   | `null`            | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)

---

### DELETE `/conversations/{conversationId}` — 대화 기록 하나 바로 삭제

- **인증 요구사항**: ✅
- **설명**: 휴지통 없이 즉시 물리 삭제하고, 연결된 대기 해석 요청을 취소/삭제한다. 그 대화가 만든 아이템과 연결은 유지하되, 대화 기록 삭제 후에는 해당 기록에 연결된 결과의 되돌리기를 허용하지 않는다. 다른 기기에는 증분 동기화에서 `CONVERSATION` 삭제 tombstone을 내려준다. 요청 본문은 없습니다.

**Path Variables**

| 변수             | 타입   | 설명            |
| ---------------- | ------ | --------------- |
| `conversationId` | `UUID` | 내 대화 기록 ID |

**Query Parameters**

| 파라미터  | 타입   | 필수 | 기본값 | 설명                      |
| --------- | ------ | ---- | ------ | ------------------------- |
| `writeId` | `UUID` | ✅   | 없음   | 대화 삭제의 재전송 식별자 |

**Response 200 OK**

| 필드      | 타입      | 설명                   |
| --------- | --------- | ---------------------- |
| `success` | `Boolean` | `true`                 |
| `data`    | `null`    | 성공, 추가 데이터 없음 |
| `error`   | `null`    | 오류 없음              |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)

---

### DELETE `/conversations` — 대화 기록 전체 바로 삭제

- **인증 요구사항**: ✅
- **설명**: 확인을 거쳐 현재 계정의 전체 기록을 휴지통 없이 삭제하고 다른 기기에 증분 삭제 신호를 전파한다. 아이템은 남기고, 삭제된 기록들에 연결된 결과의 되돌리기는 허용하지 않는다. 요청 본문은 없습니다.

**Query Parameters**

| 파라미터    | 타입      | 필수 | 기본값 | 설명                                  |
| ----------- | --------- | ---- | ------ | ------------------------------------- |
| `confirmed` | `Boolean` | ✅   | 없음   | `true`만 허용. FE 전체 삭제 확인 완료 |
| `writeId`   | `UUID`    | ✅   | 없음   | 전체 삭제의 재전송 식별자             |

**Response 200 OK**

| 필드      | 타입      | 설명                   |
| --------- | --------- | ---------------------- |
| `success` | `Boolean` | `true`                 |
| `data`    | `null`    | 성공, 추가 데이터 없음 |
| `error`   | `null`    | 오류 없음              |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **400 Bad Request**: 확인 누락 (`WRITE_REJECTED_VALIDATION`)

---

## 13. 시간표 캡처 인식 (Timetable OCR)

> F-34. 캡처 인식은 시간표 만드는 페이지의 보정 자료일 뿐이다. 인식 결과를 아이템으로 바로 저장하지 않는다. 확정 후 `/batches`의 `TIMETABLE_CREATE`로 새 컬렉션·아이템을 함께 저장한다.

### POST `/ocr/timetables` — 시간표 캡처 서버 인식

- **인증 요구사항**: ✅
- **선행 조건**: 서버 캡처 인식 동의 `ocrServerConsent=true`
- **설명**: 이미지의 이름·요일·시간·장소 후보를 반환한다. FE가 그룹·이름·시간을 보정하고 최종 묶음을 확정한다. 이미지·인식 결과를 로그나 디스크에 남기지 않고 메모리에서 즉시 처리 후 폐기한다.

**Headers**

| 헤더           | 값                    | 필수 |
| -------------- | --------------------- | ---- |
| `Content-Type` | `multipart/form-data` | ✅   |

**Request Body (`multipart/form-data`)**

| 필드      | 타입     | 필수 | Validation                                                        | 설명             |
| --------- | -------- | ---- | ----------------------------------------------------------------- | ---------------- |
| `capture` | `Binary` | ✅   | `image/png`, `image/jpeg`, `image/webp`, `image/heic` (최대 10MB) | 시간표 화면 캡처 |

**Response 200 OK**

| 필드      | 타입                      | 설명          |
| --------- | ------------------------- | ------------- |
| `success` | `Boolean`                 | `true`        |
| `data`    | `TimetableRecognitionDto` | 공통 DTO 참조 |
| `error`   | `null`                    | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **403 Forbidden**: 동의 없음 (`OCR_CONSENT_REQUIRED`)
- **429 Too Many Requests**: OCR 한도 (`OCR_RATE_LIMITED`)
- **502 Bad Gateway**: OCR 실패·상한 초과 (`OCR_TIMEOUT_OR_INVALID_RESULT`)

---

## 14. ICS 가져오기 (ICS Import)

> F-42. `.ics` 파싱·직렬화·재가져오기 값 비교·내보내기는 FE가 한다. 서버는 원본 파일을 파싱하거나 사용자가 쓴 글을 비교하지 않는다. 바이너리 다운로드 API는 두지 않는다.

### POST `/ics/imports` — FE가 해석·비교한 ICS 묶음 저장

- **인증 요구사항**: ✅
- **설명**: FE의 생성·수정·회차 예외 계획과 마지막 가져온 기준값을 한 트랜잭션으로 저장한다. UID가 기존 내 기록과 맞으면 기존 아이템을 갱신하고 다른 컬렉션을 골랐어도 소속은 바꾸지 않는다. FE가 보호한 수정 칸·손댄 회차는 덮지 않는다. 기준 버전이 바뀌면 전체를 `409 BATCH_CONFLICT`로 거절해 다시 비교하게 한다. 시간대별로 분리 내보낸 시간표(`X-BEDROCK-TIMETABLE` 확장 속성 포함)의 다시 가져오기는 `ignoreUidMatching=true`로 보내 UID 갱신 대신 고른 컬렉션에 새 아이템으로 생성한다.

**Request Body (`IcsImportRequest`)**

| 필드           | 타입                      | 필수 | Validation                    | 설명                                  |
| -------------- | ------------------------- | ---- | ----------------------------- | ------------------------------------- |
| `batchId`      | `UUID`                    | ✅   | 재전송 시 고정                | `ICS_IMPORT` 묶음                     |
| `collectionId` | `UUID`                    | ✅   | 내 활성 컬렉션                | 신규 아이템의 소속                    |
| `entries`      | `List<IcsImportEntryDto>` | ✅   | 하나 이상, UID·회차 구조 검증 | FE가 필드별 비교한 계획               |
| `warnings`     | `List<WriteWarningDto>`   | ✅   | 없으면 `[]`                   | FE가 보호한 칸·들일 수 없는 회차 안내 |

**Response 200 OK**

| 필드      | 타입                  | 설명          |
| --------- | --------------------- | ------------- |
| `success` | `Boolean`             | `true`        |
| `data`    | `BatchWriteResultDto` | 공통 DTO 참조 |
| `error`   | `null`                | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)
- **404 Not Found**: 대상이 없거나 요청자의 소유가 아님 (`RESOURCE_NOT_FOUND`)
- **400 Bad Request**: 변환된 ICS 구조 오류 (`INVALID_ICS_FORMAT`)
- **400 Bad Request**: 원자 저장 실패 (`BATCH_ATOMIC_WRITE_FAILED`)
- **409 Conflict**: 비교 이후 대상 변경 (`BATCH_CONFLICT`)

---

## 15. 시스템 알림 (System Notification)

> F-41·F-45. 울린 알람과 기기별 쓰기 결과는 FE에 둔다. 서버 안내와 풀지 않은 충돌만 서버에서 공유한다. 서버 푸시·이메일 알람·웹훅은 범위 밖이다.

### GET `/notifications/system` — 서버 안내·삭제 대상 쓰기 안내 조회

- **인증 요구사항**: ✅
- **설명**: 서버 안내와 소프트 삭제된 대상에 반영한 쓰기 안내를 반환한다. 서버 공지는 사용자마다 한 줄로 넣는다. 풀지 않은 충돌은 `/conflicts` 또는 동기화 데이터와 FE가 합쳐 보여 준다. 요청 본문은 없습니다.

**Query Parameters**

| 파라미터 | 타입      | 필수 | 기본값 | 설명                     |
| -------- | --------- | ---- | ------ | ------------------------ |
| `cursor` | `String`  | -    | 없음   | 페이지 커서              |
| `limit`  | `Integer` | -    | `50`   | 페이지 크기 (최대 `200`) |

**Response 200 OK**

| 필드      | 타입                                        | 설명          |
| --------- | ------------------------------------------- | ------------- |
| `success` | `Boolean`                                   | `true`        |
| `data`    | `CursorPageResponse<SystemNotificationDto>` | 공통 DTO 참조 |
| `error`   | `null`                                      | 오류 없음     |

**예외 응답**

- **401 Unauthorized**: 인증 세션이 없거나 종료됨 (`UNAUTHORIZED`)

---

## 공통 에러 응답

### 공통 에러 응답 구조

| 필드            | 타입               | 설명                           |
| --------------- | ------------------ | ------------------------------ |
| `success`       | `Boolean`          | 항상 `false`                   |
| `data`          | `null`             | 실패 응답에는 성공 데이터 없음 |
| `error`         | `ErrorBody`        | 오류 객체                      |
| `error.code`    | `String`           | 기계가 분기할 에러 코드        |
| `error.message` | `String`           | 한국어 설명                    |
| `error.details` | `ErrorDetailsDto?` | 추가 정보 또는 `null`          |

### HTTP 상태 코드별 예외 조건

| HTTP Status           | 에러 코드                                         | 예시 메시지 / 추가 정보                                          |
| --------------------- | ------------------------------------------------- | ---------------------------------------------------------------- |
| 400 Bad Request       | `INVALID_MAIL_CODE`                               | 틀림·만료·목적/세션 불일치. 구체 이유로 계정을 노출하지 않음     |
| 400 Bad Request       | `INVALID_NOT_ME_TOKEN`                            | 무효·만료·이미 소비된 "내가 아니다" 토큰                         |
| 400 Bad Request       | `WRITE_REJECTED_VALIDATION`                       | 형식·필수 칸·값 범위·비밀번호 정책 검증 실패                     |
| 400 Bad Request       | `BATCH_ATOMIC_WRITE_FAILED`                       | 묶음 전체 미반영, 실패 연산 정보만 제공                          |
| 400 Bad Request       | `DEFAULT_COLLECTION_CANNOT_DELETE`                | 기본 컬렉션 삭제 불가                                            |
| 400 Bad Request       | `INVALID_RECURRENCE_SPLIT_BOUNDARY`               | 유효하지 않은 적용 시작일·분할                                   |
| 400 Bad Request       | `INVALID_ICS_FORMAT`                              | FE가 변환한 ICS 구조·회차 값 검증 실패                           |
| 401 Unauthorized      | `UNAUTHORIZED`                                    | 인증 세션 없음·종료. 종료 사유는 확인 가능한 경우만              |
| 401 Unauthorized      | `INVALID_CREDENTIALS`                             | 이메일·비밀번호·OAuth 검증 실패·잠금의 통일 응답                 |
| 403 Forbidden         | `ACCOUNT_MISMATCH`                                | 웹 탭의 계정과 세션 계정 불일치                                  |
| 403 Forbidden         | `AI_CONSENT_REQUIRED`                             | AI 해석 동의 없음                                                |
| 403 Forbidden         | `OCR_CONSENT_REQUIRED`                            | 서버 캡처 인식 동의 없음                                         |
| 404 Not Found         | `RESOURCE_NOT_FOUND`                              | 없는 대상 또는 다른 사용자의 대상. 응답 동일                     |
| 409 Conflict          | `TIMETABLE_SLOT_OVERLAP`                          | 같은 컬렉션에서 기간이 겹치는 시간대 충돌                        |
| 409 Conflict          | `UNDO_BLOCKED_BY_LATER_CHANGE`                    | 이후 쓰기가 남아 있음                                            |
| 409 Conflict          | `POSTPONE_UNDO_NOT_ALLOWED`                       | 이월본 상태·순서가 취소 조건과 다름                              |
| 409 Conflict          | `BATCH_CONFLICT`                                  | 원자 저장의 기준 버전 변경으로 묶음 전체 롤백                    |
| 409 Conflict          | `RESOURCE_VERSION_CHANGED`                        | 설정·컬렉션·보기 상태·대화 기록 등의 기준 버전 변경              |
| 409 Conflict          | `CONFLICT_CHANGED`                                | 선택 뒤 후보 집합·현재 칸 버전이 변경됨                          |
| 409 Conflict          | `AI_REQUEST_CHANGED`                              | 다른 기기의 답으로 대기 요청 버전이 변경됨                       |
| 409 Conflict          | `IDEMPOTENCY_KEY_REUSED`                          | 같은 `writeId`·`batchId`로 다른 요청 본문 제출                   |
| 409 Conflict          | `SYNC_CURSOR_EXPIRED`                             | 증분 커서 보존 기한 만료로 전체 재동기화 필요                    |
| 409 Conflict          | `UNDO_NOT_SUPPORTED`                              | 대화 기록 삭제 후 취소 또는 미지원 연산                          |
| 410 Gone              | `WRITE_REJECTED_PURGED`                           | 본인 대상의 물리 삭제가 확인된 쓰기. 타인 대상 여부는 숨김       |
| 429 Too Many Requests | `MAIL_CODE_RATE_LIMITED`                          | 재발송·요청자·서비스 한도. 계정 상태 노출 방지                   |
| 429 Too Many Requests | `AI_RATE_LIMITED`                                 | AI 호출 한도 초과                                                |
| 429 Too Many Requests | `OCR_RATE_LIMITED`                                | OCR 호출 한도 초과                                               |
| 502 Bad Gateway       | `AI_TIMEOUT_OR_INVALID_SCHEMA`                    | AI 시간 상한·구조화 출력 실패                                    |
| 502 Bad Gateway       | `OCR_TIMEOUT_OR_INVALID_RESULT`                   | OCR 시간 상한·결과 실패                                          |
| 500 / 503             | `INTERNAL_SERVER_ERROR` / `TEMPORARY_UNAVAILABLE` | 일시 서버 오류·의존 서비스 장애 (FE는 같은 식별자로 재시도 가능) |

> `4xx` 검증·권한·물리 삭제 거절은 같은 쓰기를 자동 재시도하지 않고 해당 쓰기와 그것에 의존한 뒤 쓰기를 FE에서 되돌린다. `409`는 정본을 갱신해 사용자가 다시 판단한다. 연결 실패·일시 서버 오류(`500`/`503`)는 같은 식별자로 재시도한다. `429`는 제한이 풀리기 전 자동 반복하지 않는다. 저장 결과가 불명확하면 롤백·신규 생성으로 단정하지 말고 기존 묶음 상태를 확인한다. 일반 칸 충돌이 `200`으로 후보 보존된 경우는 거절과 다르다.

---

## 식별자 / 자유 문자열 (Enum 아님)

| 필드                                          | 예시                                                                                                                                                                                                                                                                                                                                                                                                     | 비고                                                                                                                                                                                                                                                                                                                                                                                                      |
| --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `itemId`·`collectionId`·`writeId`·`batchId`   | UUID 문자열                                                                                                                                                                                                                                                                                                                                                                                              | 기기 생성 ID. 재전송에서 유지. ID는 비밀이 아니며 타인 행을 덮지 않음                                                                                                                                                                                                                                                                                                                                     |
| `requestId`·`operationId`·`resultId`          | UUID 문자열                                                                                                                                                                                                                                                                                                                                                                                              | 요청·연산·되돌릴 결과를 구분                                                                                                                                                                                                                                                                                                                                                                              |
| `fieldKey`                                    | `NAME`, `BODY`, `LOCATION`, `TAGS`, `COLOR`, `COLLECTION`, `START_AT`, `END_AT`, `START_DATE`, `END_DATE`, `IS_ALL_DAY`, `TODO_STATUS`, `DUE_AT`, `PRIORITY`, `ESTIMATED_MINUTES`, `ALARMS`, `RECURRENCE_RULE`, `TIMETABLE_SLOTS`, `EXCEPTION:2026-11-09`, `EXCEPTION:2026-11-09:<slotId>`, `TIME_ZONE`, `HAS_SCHEDULE`, `HAS_TODO`, `HAS_NOTE`, `TIMETABLE_PERIOD`, `PENDING_INBOX`, `EXTRA_FIELD:<키>` | 논리 칸 키. 시작과 끝을 개별 칸으로 분리해 충돌 추적(F-45). 시간표 복수 시간대 회차는 `slotId`까지 포함. `DUE_AT`은 `dueAt`·`dueDate`를 함께 맡고 값 꼴은 `{ dueAt, dueDate }`다. 둘 중 하나만 값이 있거나 둘 다 `null`이고, PATCH는 둘을 함께 바꾼다. `TIMETABLE_PERIOD`는 `periodStart`·`periodEnd` 한 칸, `HAS_*`는 속성 더하기·빼기, `EXTRA_FIELD:<키>`의 키는 영문자로 시작하는 영숫자·밑줄 64자까지 |
| `inputConfigCode`                             | `SCHEDULE`, `TODO`, `NOTE`, `TIMETABLE`, `SCHEDULE_NOTE`, `TODO_NOTE`, `SCHEDULE_TODO`                                                                                                                                                                                                                                                                                                                   | 서버 시드의 구성 ID. 아이템 종류가 아님                                                                                                                                                                                                                                                                                                                                                                   |
| `viewKey`                                     | `CALENDAR_LAST_VIEW`, `WEB_WEEK_MODE`, `DAY_BOARD`, `TIMETABLE`, `TODO_MODULE`, `NOTE_MODULE`, `ARCHIVE_MODULE`                                                                                                                                                                                                                                                                                          | 등록형 보기 ID. 새 모듈을 사용자가 추가하는 기능은 아님                                                                                                                                                                                                                                                                                                                                                   |
| `icon`·`commandId`·`noticeCode`·`valueType`   | 아이콘·앱 명령·안내·필드 타입 식별자                                                                                                                                                                                                                                                                                                                                                                     | 허용 등록표·기본값은 미정. 자유 실행 코드가 아님                                                                                                                                                                                                                                                                                                                                                          |
| `rrule`·`externalUid`                         | `FREQ=WEEKLY;BYDAY=MO`, ICS UID                                                                                                                                                                                                                                                                                                                                                                          | 표준 문자열·외부 식별자. UID는 사용자별로 소유 확인                                                                                                                                                                                                                                                                                                                                                       |
| `referenceTimeZone`                           | `Asia/Seoul`                                                                                                                                                                                                                                                                                                                                                                                             | IANA 시간대 문자열                                                                                                                                                                                                                                                                                                                                                                                        |
| `cursor`·`revision`                           | 불투명 문자열                                                                                                                                                                                                                                                                                                                                                                                            | FE가 구조·서버 시퀀스·만료 규칙을 해석하지 않음                                                                                                                                                                                                                                                                                                                                                           |
| `name`·`body`·`location`·`tags`·`aiGuideline` | 사용자가 적은 글                                                                                                                                                                                                                                                                                                                                                                                         | 암호화 저장. 서버 검색·정렬·글 동등성 비교 금지                                                                                                                                                                                                                                                                                                                                                           |
| 위키링크                                      | `[[item:id                                                                                                                                                                                                                                                                                                                                                                                               | alias]]`                                                                                                                                                                                                                                                                                                                                                                                                  | FE가 파싱하고 언급 집합을 함께 전송. 서버는 본문을 파싱하지 않음 |

---

## 공통 DTO

> `String`·`UUID`는 JSON 문자열, `Boolean`은 불리언, `Integer`는 정수, `Int64`는 64비트 정수(JSON 정밀도 보존을 위해 10진 문자열 허용)다. `Instant`는 UTC `YYYY-MM-DDTHH:mm:ss.SSSZ`, `LocalDate`는 `YYYY-MM-DD`, `LocalTime`은 `HH:mm`이다. `T?`는 `null` 가능을 뜻하며 PATCH의 생략은 유지다. `List<T>`는 배열, `Map<K,V>`는 키별 객체다. `JsonValue`는 문자열·숫자·불리언·null·그 배열/객체의 JSON 값이며 등록된 칸 타입으로 검증한다. `WorkspacePayload`는 아래 변경 DTO에서 정의한 자원 DTO의 합집합 타입이다. 별도 설명이 없으면 목록은 `null` 대신 `[]`를 사용한다. 요청 전용 일부 변경 DTO의 생략 가능성과 명시적 `null` 허용은 설명을 함께 따른다.

### ApiResponse<T>

| 필드      | 타입         | 설명                                   |
| --------- | ------------ | -------------------------------------- |
| `success` | `Boolean`    | 성공 `true`, 실패 `false`              |
| `data`    | `T?`         | 성공 데이터. `Void` 성공·실패는 `null` |
| `error`   | `ErrorBody?` | 성공은 `null`, 실패는 오류 객체        |

### ErrorBody

| 필드      | 타입               | 설명                                     |
| --------- | ------------------ | ---------------------------------------- |
| `code`    | `String`           | 에러 코드. 최상위 `code`를 추가하지 않음 |
| `message` | `String`           | 사용자용 한국어 설명                     |
| `details` | `ErrorDetailsDto?` | 보충 정보가 없으면 `null`                |

### ErrorDetailsDto

| 필드                | 타입                   | 설명                                       |
| ------------------- | ---------------------- | ------------------------------------------ |
| `fieldErrors`       | `List<FieldErrorDto>?` | 필드 검증 오류                             |
| `retryAfterSeconds` | `Integer?`             | 재시도 대기 초. 계정 유무를 드러내면 안 됨 |
| `sessionEndReason`  | `SessionEndReason?`    | 확인 가능한 종료 사유. 모르면 `UNKNOWN`    |
| `batchId`           | `UUID?`                | 관련 실패 묶음                             |
| `operationId`       | `UUID?`                | 실패한 묶음 연산                           |
| `currentRevision`   | `String?`              | 현재 요청·리소스 버전                      |
| `conflictIds`       | `List<UUID>?`          | 관련 충돌 ID 목록                          |

오류 메시지·상세에 비밀번호·코드·토큰·사용자 본문을 넣지 않는다.

### FieldErrorDto

| 필드     | 타입     | 설명                                          |
| -------- | -------- | --------------------------------------------- |
| `field`  | `String` | 오류 필드 경로                                |
| `reason` | `String` | 검증 규칙 설명. 거절된 민감값은 포함하지 않음 |

### CursorPageResponse<T>

| 필드         | 타입      | 설명                            |
| ------------ | --------- | ------------------------------- |
| `items`      | `List<T>` | 현재 페이지. 결과가 없으면 `[]` |
| `nextCursor` | `String?` | 다음 페이지가 없으면 `null`     |
| `hasMore`    | `Boolean` | 다음 페이지 존재 여부           |

커서는 불투명 문자열이며 클라이언트가 시퀀스·시각으로 해석하지 않는다.

### UserDto

| 필드        | 타입      | 설명                         |
| ----------- | --------- | ---------------------------- |
| `id`        | `UUID`    | 현재 계정 ID                 |
| `email`     | `String`  | 확인된 이메일, 소문자 정규화 |
| `name`      | `String`  | 계정 이름                    |
| `createdAt` | `Instant` | 계정 생성 시각               |

### SessionDto

| 필드               | 타입                | 설명                                          |
| ------------------ | ------------------- | --------------------------------------------- |
| `authenticated`    | `Boolean`           | 로그인 여부                                   |
| `user`             | `UserDto?`          | 익명 세션이면 `null`                          |
| `platform`         | `Platform?`         | 로그인 세션의 플랫폼                          |
| `rememberMe`       | `Boolean`           | iOS·iPadOS는 `true`                           |
| `idleExpiresAt`    | `Instant`           | 무활동 만료 예정 시각                         |
| `sessionEndReason` | `SessionEndReason?` | 비로그인 전환 이유를 확인할 수 있을 때만 반환 |

세션 ID는 JSON으로 반환하지 않는다. iOS는 쿠키 자격을 이 기기 전용 Keychain에 보관한다. 종료 사유가 확인되지 않았다는 이유로 iOS의 보내지 않은 쓰기를 즉시 삭제하지 않는다.

### DeviceContextDto

| 필드        | 타입       | 설명                                                                                                   |
| ----------- | ---------- | ------------------------------------------------------------------------------------------------------ |
| `platform`  | `Platform` | `IOS`·`IPADOS`·`WEB`                                                                                   |
| `modelName` | `String?`  | 최대 100자, 표시용                                                                                     |
| `timeZone`  | `String?`  | 기기의 IANA 시간대. 가입·첫 로그인·새로 시작에서 계정 기본 시간대(`SettingsDto.timeZone`)의 처음 값    |
| `deviceId`  | `String?`  | iOS·iPadOS의 이 기기 전용 Keychain 식별값. 웹은 본문 대신 HttpOnly 기기 쿠키(`BEDROCK_DEVICE_ID`) 사용 |

식별값은 로그인 알림 중복 억제에만 쓰며 인증 자격이 아니다.

### DeviceDto

| 필드           | 타입       | 설명                 |
| -------------- | ---------- | -------------------- |
| `id`           | `UUID`     | 서버 기기 레코드 ID  |
| `platform`     | `Platform` | 플랫폼               |
| `modelName`    | `String?`  | 기기가 보낸 모델명   |
| `lastActiveAt` | `Instant`  | 마지막 활동          |
| `isCurrent`    | `Boolean`  | 현재 로그인 기기인지 |

### MailChallengeDto

| 필드                | 타입           | 설명                      |
| ------------------- | -------------- | ------------------------- |
| `nextStep`          | `AuthNextStep` | 항상 `MAIL_CODE_REQUIRED` |
| `expiresAt`         | `Instant`      | 코드 발송 기준 10분 만료  |
| `resendAvailableAt` | `Instant`      | 발송 기준 60초 뒤         |

없는 계정·기존 활성 계정에도 같은 모양의 응답을 반환한다. 실제 발송 여부·주소별 잠금·반송 여부는 반환하지 않는다.

### AuthStepDto

| 필드                       | 타입                   | 설명                                        |
| -------------------------- | ---------------------- | ------------------------------------------- |
| `nextStep`                 | `AuthNextStep`         | 다음 단계                                   |
| `session`                  | `SessionDto?`          | `AUTHENTICATED`일 때                        |
| `challenge`                | `MailChallengeDto?`    | 추가 코드가 필요할 때                       |
| `availableRecoveryActions` | `List<RecoveryAction>` | 유예 확인 완료 후 허용되는 선택. 그 외 `[]` |

재설정 완료는 `PASSWORD_RESET_COMPLETED`이며 새 로그인 세션을 발급하지 않는다. 복구 선택 단계에서도 정식 로그인 권한을 주지 않는다.

### OAuthChallengeDto

| 필드               | 타입            | 설명                           |
| ------------------ | --------------- | ------------------------------ |
| `provider`         | `OAuthProvider` | 로그인 제공자                  |
| `authorizationUrl` | `String?`       | 웹 인가 시작 주소              |
| `nonce`            | `String?`       | Apple에 전달할 원문 nonce 값   |
| `state`            | `String?`       | 웹의 1회용 state               |
| `expiresAt`        | `Instant`       | 대기 인가의 만료(발급 후 10분) |

### NotMeInfoDto

| 필드        | 타입            | 설명                                          |
| ----------- | --------------- | --------------------------------------------- |
| `provider`  | `OAuthProvider` | 끊을 새 제공자 연결                           |
| `expiresAt` | `Instant`       | 링크 발급 7일 뒤                              |
| `canRevoke` | `Boolean`       | 현재 토큰 사용 가능. 조회만으로 소비하지 않음 |

### AccountDeletionDto

| 필드         | 타입      | 설명                        |
| ------------ | --------- | --------------------------- |
| `deletedAt`  | `Instant` | 탈퇴 반영 시각              |
| `purgeAfter` | `Instant` | 30일 뒤 물리 삭제 예정 시각 |

### WorkspaceDto

| 필드                  | 타입                           | 설명                                                                           |
| --------------------- | ------------------------------ | ------------------------------------------------------------------------------ |
| `userId`              | `UUID`                         | 소유 계정                                                                      |
| `workspaceId`         | `UUID`                         | 워크스페이스 ID. MVP는 계정마다 하나이고 서버가 사용자의 워크스페이스를 고른다 |
| `cursor`              | `String`                       | 스냅샷 경계 커서                                                               |
| `serverTime`          | `Instant`                      | 응답 기준 서버 시각                                                            |
| `items`               | `List<ItemDto>`                | 활성·소프트 삭제 아이템, 계산 회차 없음                                        |
| `collections`         | `List<CollectionDto>`          | 컬렉션과 삭제 상태                                                             |
| `links`               | `List<LinkDto>`                | 연결 전체                                                                      |
| `exceptions`          | `List<OccurrenceExceptionDto>` | 저장된 회차 예외                                                               |
| `conflicts`           | `List<ConflictDto>`            | 풀지 않은 충돌                                                                 |
| `settings`            | `SettingsDto`                  | 사용자 설정                                                                    |
| `viewStates`          | `List<ViewStateDto>`           | 보기 상태                                                                      |
| `pins`                | `List<PinDto>`                 | 고정 상태                                                                      |
| `inputScreenConfigs`  | `List<InputScreenConfigDto>`   | 입력 구성                                                                      |
| `trashBundles`        | `List<TrashBundleDto>`         | 보관 중 삭제 묶음                                                              |
| `icsImportRecords`    | `List<IcsImportRecordDto>`     | UID·FE 비교용 마지막 값                                                        |
| `systemNotifications` | `List<SystemNotificationDto>`  | 서버가 보관하는 안내                                                           |

모든 목록에서 빈 배열은 해당 데이터가 없음을 뜻한다. 대화 본문·검색어·울린 알람은 포함하지 않는다.

### WorkspaceChangesDto

| 필드         | 타입                       | 설명                   |
| ------------ | -------------------------- | ---------------------- |
| `changes`    | `List<WorkspaceChangeDto>` | 순서대로 적용할 변경   |
| `nextCursor` | `String`                   | 현재 페이지까지의 커서 |
| `hasMore`    | `Boolean`                  | 이어서 받을 변경 존재  |
| `serverTime` | `Instant`                  | 서버 시각              |

### WorkspaceChangeDto

| 필드           | 타입                    | 설명                                                        |
| -------------- | ----------------------- | ----------------------------------------------------------- |
| `resourceType` | `WorkspaceResourceType` | 변경 자원                                                   |
| `operation`    | `ChangeOperation`       | 추가·수정 또는 제거                                         |
| `resourceId`   | `String`                | UUID 또는 보기·구성 코드                                    |
| `syncSeq`      | `Int64`                 | 전송 정렬용 단조 증가 값. 커서를 대체하지 않음              |
| `payload`      | `WorkspacePayload?`     | `UPSERT`일 때 해당 자원 DTO. `DELETE`·`INVALIDATE`는 `null` |
| `tombstone`    | `TombstoneDto?`         | 제거·기록 삭제 시                                           |

`WorkspacePayload`는 자원에 따라 `ItemDto`·`CollectionDto`·`LinkDto`·`OccurrenceExceptionDto`·`ConflictDto`·`SettingsDto`·`ViewStateDto`·`PinDto`·`TrashBundleDto`·`IcsImportRecordDto`·`SystemNotificationDto` 중 하나다. 대화 변경은 본문 대신 ID와 삭제/무효화 신호만 전달한다.

### TombstoneDto

| 필드           | 타입                    | 설명           |
| -------------- | ----------------------- | -------------- |
| `resourceType` | `WorkspaceResourceType` | 제거된 자원    |
| `resourceId`   | `String`                | 제거된 ID      |
| `deletedAt`    | `Instant`               | 확정 제거 시각 |

물리 삭제와 연결·고정 해제, 대화 기록 삭제를 다른 기기에서도 지울 수 있도록 증분 동기화 응답에 포함한다.

### ItemDto

| 필드            | 타입                    | 설명                                                                                                  |
| --------------- | ----------------------- | ----------------------------------------------------------------------------------------------------- |
| `id`            | `UUID`                  | 기기가 정한 아이템 ID                                                                                 |
| `collectionId`  | `UUID`                  | 컬렉션 하나에 소속                                                                                    |
| `fields`        | `ItemFieldsDto`         | 아이템의 속성                                                                                         |
| `fieldVersions` | `List<FieldVersionDto>` | 칸별 기준 쓰기                                                                                        |
| `conflictIds`   | `List<UUID>`            | 풀지 않은 충돌 ID, 없으면 `[]`                                                                        |
| `createdAt`     | `Instant`               | 기기가 만든 시각(`ItemCreateSpec.createdAt`). 서버가 받은 시각은 감사용으로 따로 두고 내려주지 않는다 |
| `updatedAt`     | `Instant`               | 서버 최종 수정 시각                                                                                   |
| `deletedAt`     | `Instant?`              | 활성이면 `null`                                                                                       |
| `purgeAfter`    | `Instant?`              | 휴지통 물리 삭제 예정                                                                                 |
| `trashBundleId` | `UUID?`                 | 속한 삭제 묶음                                                                                        |
| `syncSeq`       | `Int64`                 | 서버 변경 번호. 칸 버전·충돌 후보가 바뀌어도 오른다                                                   |

`type`·`nature` 같은 아이템 종류는 저장하지 않는다. 성격은 FE가 속성으로 판정한다.

### ItemFieldsDto

| 필드               | 타입                      | 설명                                                                                                                                                                                                                               |
| ------------------ | ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `name`             | `String?`                 | 필수 아님. 비면 FE가 본문 첫 줄·자리 표시를 사용                                                                                                                                                                                   |
| `tags`             | `List<String>`            | 통째로 한 칸. 없음은 `[]`                                                                                                                                                                                                          |
| `color`            | `SwatchColor?`            | `null`이면 컬렉션 색                                                                                                                                                                                                               |
| `hasSchedule`      | `Boolean`                 | 일정 속성 유무                                                                                                                                                                                                                     |
| `startAt`          | `Instant?`                | 시간 일정의 시작                                                                                                                                                                                                                   |
| `endAt`            | `Instant?`                | 끝. 명시적으로 없으면 길이 0 표지                                                                                                                                                                                                  |
| `startDate`        | `LocalDate?`              | 종일 시작일                                                                                                                                                                                                                        |
| `endDate`          | `LocalDate?`              | 종일 배타적 끝. 1일 일정은 시작일 다음 날                                                                                                                                                                                          |
| `timeZone`         | `String?`                 | 아이템의 표준 시간대(IANA). 만들 때 계정 기본 시간대(`SettingsDto.timeZone`)를 넣는다. 시각 값·반복 회차의 날짜·날짜 없는 아이템의 만든 날을 이 시간대로 정한다. `null`은 떠 있는 시각이며 MVP 기기는 시각 있는 아이템에 늘 넣는다 |
| `isAllDay`         | `Boolean`                 | 종일 여부                                                                                                                                                                                                                          |
| `location`         | `String?`                 | 사람이 적은 장소                                                                                                                                                                                                                   |
| `hasTodo`          | `Boolean`                 | 할 일 속성 유무                                                                                                                                                                                                                    |
| `todoState`        | `TodoStateDto?`           | 상태·완료 시각을 합친 동기화 칸                                                                                                                                                                                                    |
| `dueAt`            | `Instant?`                | 시각을 고른 마감                                                                                                                                                                                                                   |
| `dueDate`          | `LocalDate?`              | 날짜만 고른 마감. `dueAt`과 둘 중 하나만 값을 갖는다(칸 키 `DUE_AT`)                                                                                                                                                               |
| `priority`         | `Integer?`                | 1~5, 1이 가장 높음                                                                                                                                                                                                                 |
| `estimatedMinutes` | `Integer?`                | 양의 분 단위                                                                                                                                                                                                                       |
| `alarms`           | `List<Integer>`           | 기준 시각 몇 분 전. 0은 정시. 목록 전체가 한 칸                                                                                                                                                                                    |
| `hasNote`          | `Boolean`                 | 노트 속성 유무                                                                                                                                                                                                                     |
| `body`             | `String?`                 | 마크다운 원문, 본문 전체가 한 칸                                                                                                                                                                                                   |
| `recurrence`       | `RecurrenceSpec?`         | 반복 없음은 `null`                                                                                                                                                                                                                 |
| `timetable`        | `TimetableDto?`           | 시간표 없음은 `null`, 생성 후 속성 전환 불가                                                                                                                                                                                       |
| `pendingInbox`     | `PendingInboxDto?`        | 정리 대기 아님은 `null`                                                                                                                                                                                                            |
| `extraFields`      | `Map<String, JsonValue>?` | 등록된 확장 속성만 허용                                                                                                                                                                                                            |

생성 때 일정 시작·할 일 상태·노트 본문·시간표 시간대를 구성에 맞춰 검증한다. 일정+노트·할 일+노트의 본문은 선택이다. 생성 시 끝을 생략하면 시작+예상 소요시간 또는 1시간을 사용하고, 종일은 하루를 덮는다. `PATCH`에서 생략과 `null`은 구분한다. 속성 제거 시 관련 칸 제거도 함께 검증한다.

### TodoStateDto

| 필드          | 타입         | 설명                                            |
| ------------- | ------------ | ----------------------------------------------- |
| `status`      | `TodoStatus` | 할 일 상태                                      |
| `completedAt` | `Instant?`   | `DONE`으로 바꾼 기기의 시각. 다른 상태는 `null` |

`status`와 `completedAt`을 한 칸(`TODO_STATUS`)으로 저장·충돌 처리한다. `POSTPONED`를 날짜까지 골라 처리할 때는 미룸 묶음을 사용한다.

### RecurrenceSpec

| 필드            | 타입              | 설명                                                                                                                                                                                        |
| --------------- | ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `rrule`         | `String`          | iCalendar RRULE, 최대 500자. 일정·할 일은 n일마다·매주·매월(같은 날짜)·매년(같은 날짜). 그 달에 없는 날은 건너뛴다(RFC 5545). n번째 요일·말일 같은 그 밖의 꼴은 `WRITE_REJECTED_VALIDATION` |
| `rdates`        | `List<LocalDate>` | ICS로 들어온 추가 회차 날짜만, 없으면 `[]`                                                                                                                                                  |
| `exdates`       | `List<LocalDate>` | ICS로 들어온 삭제 회차 날짜만, 없으면 `[]`. 사용자가 지운 회차의 정본은 회차 예외(`isDeletedOccurrence`)다                                                                                  |
| `untilDate`     | `LocalDate?`      | 마지막 허용 날짜. `null`은 종료 없음                                                                                                                                                        |
| `seriesRootId`  | `UUID?`           | 반환 시 나뉜 마스터 사슬의 루트 ID. 일반 생성에서는 서버가 설정                                                                                                                             |
| `effectiveFrom` | `LocalDate?`      | 나뉜 마스터의 적용 시작일                                                                                                                                                                   |

전체 반복 규칙은 통째로 한 칸(`RECURRENCE_RULE`)이다. 기준 날짜는 시작, 없으면 마감(`dueAt` 또는 `dueDate`)이다. 날짜 값(`rdates`·`exdates`·`untilDate`·`effectiveFrom`)과 회차 날짜는 아이템 표준 시간대의 날짜다. `rdates`·`exdates`는 반복 규칙 칸과 함께 바뀐다.

### TimetableDto

| 필드            | 타입                      | 설명                                                |
| --------------- | ------------------------- | --------------------------------------------------- |
| `slots`         | `List<TimetableSlotSpec>` | 한 개 이상. 같은 요일 여러 시간대 허용              |
| `periodStart`   | `LocalDate`               | 시작일. 화면에서 생략하면 FE가 만든 날을 채워 전송  |
| `periodEnd`     | `LocalDate?`              | 종료일 포함, `null`은 끝 없음                       |
| `seriesRootId`  | `UUID`                    | 나뉜 시간표 사슬의 루트. 처음 만들면 자기 아이템 ID |
| `effectiveFrom` | `LocalDate?`              | 나뉜 마스터의 적용 시작일                           |

시간대 목록은 한 칸(`TIMETABLE_SLOTS`), 기간은 한 칸(`TIMETABLE_PERIOD`)이다. 같은 컬렉션·겹치는 기간에서 마스터 시간대의 겹침을 금지한다(컬렉션 이동 및 휴지통 복구 시에도 대상 컬렉션의 활성 시간표 마스터와 겹침 여부를 동일하게 검사한다). 회차 하나의 이동은 겹쳐도 허용한다.

### TimetableSlotSpec

| 필드        | 타입        | 설명                      |
| ----------- | ----------- | ------------------------- |
| `slotId`    | `UUID`      | 시간대의 안정 식별자      |
| `dayOfWeek` | `Integer`   | 1=월~7=일                 |
| `startTime` | `LocalTime` | `HH:mm`, 날짜·시간대 없음 |
| `endTime`   | `LocalTime` | 같은 날, 시작보다 뒤      |

FE가 시간대를 만들 때 `slotId`를 지정하거나 생략 시 서버가 부여하며, 이후 시간표 전체 수정(`ALL`)에서는 유지되는 시간대의 기존 `slotId`를 그대로 보내 회차 예외와의 연결을 보존한다. 시간표 마스터 나누기(`THIS_AND_FUTURE`, 주 경계는 사용자 시작 요일 설정과 무관하게 월요일 00:00)에서는 선택한 주 월요일부터 적용되는 새 마스터의 새 `slotId`들로 미래 회차 예외를 재매핑한다. 수정이나 나누기 결과로 해당 요일·시간대가 사라져 대응할 `slotId`가 없어진 기존 회차 예외는 `ORPHANED_OCCURRENCE` 충돌 후보로 전환한다.

### OccurrenceRefDto

| 필드             | 타입        | 설명                                                 |
| ---------------- | ----------- | ---------------------------------------------------- |
| `masterItemId`   | `UUID`      | 반복 마스터                                          |
| `recurrenceDate` | `LocalDate` | 이동하기 전 원래 회차 날짜                           |
| `slotId`         | `UUID?`     | 시간표의 어느 시간대인지. 같은 날 여러 시간대면 필수 |

하루 여러 시간대가 있는 시간표의 회차 예외는 `(masterItemId, recurrenceDate, slotId)` 조합으로 구분하며, 시간대 교체·삭제 시 회차 예외가 조용히 사라지지 않도록 재매핑하거나 짝 없는 예외(`ORPHANED_OCCURRENCE`) 충돌 후보로 이관한다. 이동 후 날짜로 회차의 정체성을 바꾸지 않는다.

### OccurrenceExceptionDto

| 필드                  | 타입               | 설명                            |
| --------------------- | ------------------ | ------------------------------- |
| `id`                  | `UUID`             | 저장된 예외 레코드 ID           |
| `occurrence`          | `OccurrenceRefDto` | 원래 회차                       |
| `isDeletedOccurrence` | `Boolean`          | 회차 삭제 여부                  |
| `exceptionItemId`     | `UUID?`            | 수정한 회차 아이템              |
| `modifiedFields`      | `List<String>`     | 손댄 칸만. 나머지는 마스터 상속 |
| `syncSeq`             | `Int64`            | 변경 번호                       |

두 기기가 같은 회차 예외를 만들면 하나로 합친다. 충돌 버전 단위는 일반 반복 회차는 `EXCEPTION:<YYYY-MM-DD>`, 시간표 회차(`slotId` 존재 시)는 `EXCEPTION:<YYYY-MM-DD>:<slotId>`다.

### PendingInboxDto

| 필드                | 타입      | 설명                                   |
| ------------------- | --------- | -------------------------------------- |
| `referenceTime`     | `Instant` | 문장 상대 날짜의 기준                  |
| `referenceTimeZone` | `String`  | IANA 시간대                            |
| `requestId`         | `UUID?`   | 관련 원래 해석 요청 ID                 |
| `uncertainBatchId`  | `UUID?`   | 저장 성공 여부가 불명확한 기존 묶음 ID |

본문을 사용자가 고치면 그 편집 시각·시간대로 기준을 바꾼다. 앱의 내부 처리나 본문 밖 칸 변경은 기준을 바꾸지 않는다.

### FieldVersionDto

| 필드          | 타입     | 설명                                                                                                                                                                                       |
| ------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `fieldKey`    | `String` | 합치기의 논리 칸 키(`START_AT`, `END_AT`, `START_DATE`, `END_DATE`, `IS_ALL_DAY` 등 개별 분리)                                                                                             |
| `lastWriteId` | `UUID?`  | 마지막으로 반영한 쓰기. 단건 쓰기는 `writeId`, 묶음 안 연산(만들기·고치기·지우기·미루기)은 `operationId`다. 만들기가 값을 넣은 칸도 그 만들기의 ID를 남긴다. 한 번도 쓰지 않은 칸은 `null` |

기준 쓰기와 다르게 도착한 값은 충돌 후보가 된다. 이름·본문·장소·태그의 같은 값 가리기는 FE가 한다. 시작과 끝은 개별 `fieldKey`로 분리하여 칸 단위로 충돌과 쓰기 이력을 추적한다.

### ItemCreateSpec

| 필드                 | 타입            | 설명                                                                                 |
| -------------------- | --------------- | ------------------------------------------------------------------------------------ |
| `operationId`        | `UUID`          | 기기가 정한 연산 ID. 필수. 묶음 안에서 하나뿐이다                                    |
| `itemId`             | `UUID`          | 기기에서 생성한 ID                                                                   |
| `collectionId`       | `UUID?`         | 생략하면 기본 컬렉션                                                                 |
| `inputConfigCode`    | `String?`       | 직접 입력 구성 ID. 생성 가능한 속성 검증용                                           |
| `fields`             | `ItemFieldsDto` | 만들 속성                                                                            |
| `referenceTargetIds` | `List<UUID>`    | 본문의 언급 대상 전체. 없음은 `[]`                                                   |
| `createdAt`          | `Instant?`      | 기기가 만든 시각. 서버 시각보다 미래면 서버 시각으로 자르고, 없으면 서버 시각을 쓴다 |

### ItemPatchSpec

| 필드                 | 타입                 | 설명                                                             |
| -------------------- | -------------------- | ---------------------------------------------------------------- |
| `operationId`        | `UUID`               | 묶음 안의 안정 연산 ID, 칸 쓰기 추적에 사용                      |
| `itemId`             | `UUID`               | 수정 대상                                                        |
| `scope`              | `MutationScope`      | 수정 범위                                                        |
| `occurrence`         | `OccurrenceRefDto?`  | 회차 수정 시                                                     |
| `effectiveDate`      | `LocalDate?`         | 이후 전체 적용일                                                 |
| `baseFieldVersions`  | `Map<String, UUID?>` | 변경 칸별 기준 쓰기                                              |
| `changedFields`      | `ItemFieldsDto`      | 바뀐 칸만                                                        |
| `referenceTargetIds` | `List<UUID>?`        | 본문이 바뀌면 필수. 없음과 빈 배열을 구분                        |
| `materializeForLink` | `Boolean`            | 값 변경 없이 연결용 예외를 만들 때만 `true`. 일반 수정은 `false` |

### DeleteTargetDto

| 필드                            | 타입                  | 설명                                              |
| ------------------------------- | --------------------- | ------------------------------------------------- |
| `operationId`                   | `UUID`                | 기기가 정한 연산 ID. 필수. 묶음 안에서 하나뿐이다 |
| `itemId`                        | `UUID`                | 삭제 대상 또는 마스터                             |
| `scope`                         | `MutationScope`       | 단건·회차·이후 전체·전체                          |
| `occurrence`                    | `OccurrenceRefDto?`   | 회차 하나 삭제 시                                 |
| `effectiveDate`                 | `LocalDate?`          | 이후 전체 삭제 시                                 |
| `baseFieldVersions`             | `Map<String, UUID?>?` | AI의 오래된 삭제 요청을 막는 기준                 |
| `deleteDerivedCreatedFromItems` | `Boolean`             | 출처로 만든 아이템도 함께 지울지. 기본 `false`    |

### LinkSpec

| 필드           | 타입       | 설명                         |
| -------------- | ---------- | ---------------------------- |
| `sourceItemId` | `UUID`     | 연결 출발                    |
| `targetItemId` | `UUID`     | 연결 도착                    |
| `linkType`     | `LinkType` | 허용된 동작과 맞는 연결 종류 |

방향은 출처→파생 아이템, 마스터→예외, 원본→이월본, 옛 마스터→새 마스터, 본문 아이템→언급 대상이다.

### LinkDto

| 필드           | 타입       | 설명        |
| -------------- | ---------- | ----------- |
| `id`           | `UUID`     | 연결 ID     |
| `sourceItemId` | `UUID`     | 출발 아이템 |
| `targetItemId` | `UUID`     | 도착 아이템 |
| `linkType`     | `LinkType` | 연결 종류   |
| `createdAt`    | `Instant`  | 연결 생성   |
| `syncSeq`      | `Int64`    | 변경 번호   |

### MutationReceiptDto

| 필드               | 타입      | 설명                         |
| ------------------ | --------- | ---------------------------- |
| `writeId`          | `UUID`    | 적용한 쓰기                  |
| `alreadyProcessed` | `Boolean` | 재전송인지                   |
| `latestSyncSeq`    | `Int64`   | 이 쓰기에서 반영한 변경 번호 |

### WriteWarningDto

| 필드        | 타입           | 설명                                       |
| ----------- | -------------- | ------------------------------------------ |
| `code`      | `String`       | 안내 식별자. Enum이 아닌 확장 코드         |
| `itemId`    | `UUID?`        | 관련 아이템                                |
| `fieldKeys` | `List<String>` | 관련 칸. 없으면 `[]`                       |
| `message`   | `String`       | 사용자에게 보일 안내. 로그에는 남기지 않음 |

내부 경고 문자열(`"<code>|<itemId>|<fieldKeys>|<message>"` 또는 단일 `<code>`)을 파싱해 `code`, `itemId`(없으면 `null`), `fieldKeys`(없으면 `[]`), `message`로 변환하여 반환한다.

### WriteResultDto

| 필드               | 타입                    | 설명                          |
| ------------------ | ----------------------- | ----------------------------- |
| `writeId`          | `UUID`                  | 단건 쓰기 ID                  |
| `alreadyProcessed` | `Boolean`               | 같은 쓰기의 재전송            |
| `item`             | `ItemDto`               | 쓰기 후 서버 정본             |
| `appliedFieldKeys` | `List<String>`          | 반영된 칸                     |
| `conflicts`        | `List<ConflictDto>`     | 보존된 진 후보                |
| `warnings`         | `List<WriteWarningDto>` | 삭제된 대상·사슬 전파 제외 등 |
| `latestSyncSeq`    | `Int64`                 | 반영 시점의 변경 번호         |

`200`이어도 충돌 후보가 있을 수 있다. FE는 후보가 된 값을 조용히 버리지 말고 충돌을 표시한다. 검증 실패는 `4xx`이고 쓰기를 되돌리며, 충돌 후보로 보존된 쓰기와 구분한다. 같은 `writeId`로 동일한 단건 쓰기가 재전송되면 이미 처리된 응답(`alreadyProcessed = true`)을 반환하고, 본문이 다르면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.

### MutationResultDto

| 필드               | 타입             | 설명                                   |
| ------------------ | ---------------- | -------------------------------------- |
| `resultId`         | `UUID`           | 결과 하나의 식별자                     |
| `operationId`      | `UUID`           | 원래 연산 ID                           |
| `itemId`           | `UUID`           | 주요 결과 아이템                       |
| `affectedItemIds`  | `List<UUID>`     | 반복 나누기 등 연관 아이템             |
| `scope`            | `MutationScope`  | 적용 범위                              |
| `changedFieldKeys` | `List<String>`   | 바뀐 칸                                |
| `before`           | `ItemFieldsDto?` | 바꾸기 전. 생성이면 `null`             |
| `after`            | `ItemFieldsDto?` | 바꾼 뒤. 삭제는 삭제 전 속성 유지 가능 |
| `trashBundleId`    | `UUID?`          | 삭제 결과의 복구 묶음                  |
| `undoSupported`    | `Boolean`        | 현재 연산의 되돌리기 지원 여부         |

실제 반환 전후 값은 바뀐 칸만 포함한다. 표시용 요약을 서버에 평문 로그로 남기지 않는다. 되돌리기 가능 여부는 취소 요청 시점에 해당 칸의 최신 쓰기 상태와 후속 변경 유무를 기준으로 다시 검사한다.

### BatchWriteResultDto

| 필드               | 타입                      | 설명                                      |
| ------------------ | ------------------------- | ----------------------------------------- |
| `batchId`          | `UUID`                    | 확정된 실제 묶음 ID                       |
| `alreadyProcessed` | `Boolean`                 | 같은 묶음 또는 같은 AI 요청의 선저장 결과 |
| `affectedItemIds`  | `List<UUID>`              | 영향받은 전체 아이템                      |
| `results`          | `List<MutationResultDto>` | 결과별 전후 값·취소 참조                  |
| `warnings`         | `List<WriteWarningDto>`   | 보호한 값·기본값·삭제 안내                |
| `latestSyncSeq`    | `Int64`                   | 이 묶음 완료 경계 번호                    |

재전송은 암호화 저장한 응답 스냅샷을 복호화해 반환한다. FE는 오래된 스냅샷으로 더 최신 칸을 덮지 않고 증분 동기화로 정본을 맞춘다.

### BatchStatusDto

| 필드      | 타입                   | 설명                                                                 |
| --------- | ---------------------- | -------------------------------------------------------------------- |
| `batchId` | `UUID`                 | 기존 요청 ID                                                         |
| `status`  | `BatchStatus`          | 확정된 기록 상태(`COMPLETED` · `FAILED` · `UNDONE`)                  |
| `result`  | `BatchWriteResultDto?` | `COMPLETED` 또는 `UNDONE`일 때의 성공 당시 결과. `FAILED`이면 `null` |

원자 묶음 쓰기가 실패한 경우 전체 트랜잭션이 롤백되므로 별도의 실패 상세 필드를 보장하지 않는다.

### PostponeSpec

| 필드                | 타입                 | 설명                                              |
| ------------------- | -------------------- | ------------------------------------------------- |
| `operationId`       | `UUID`               | 기기가 정한 연산 ID. 필수. 묶음 안에서 하나뿐이다 |
| `source`            | `ItemTargetDto`      | 원본 또는 반복 회차                               |
| `mode`              | `PostponeMode`       | 고른 날에 이월 또는 다음 회차 건너뛰기            |
| `carryItemId`       | `UUID?`              | 이월본을 만들 때 기기에서 정한 ID                 |
| `destination`       | `ScheduleValueDto?`  | 이월할 날짜·시각. 시각 없으면 종일                |
| `baseFieldVersions` | `Map<String, UUID?>` | 원본의 상태·시간 기준 버전                        |

이월본은 미완료이며 원본의 컬렉션·마감을 물려받는다. `SKIP_TO_NEXT`는 원본 회차를 취소됨으로 두고 이월본을 만들지 않는다. 목적지가 다음 회차 날짜면 넘기기만 허용한다. 순수 상태 변경이 아닌 미루기는 서버가 세 결과를 함께 검증한다.

### ItemTargetDto

| 필드         | 타입                | 설명                         |
| ------------ | ------------------- | ---------------------------- |
| `itemId`     | `UUID`              | 일반 아이템 또는 반복 마스터 |
| `occurrence` | `OccurrenceRefDto?` | 회차를 특정할 때             |

### ScheduleValueDto

| 필드        | 타입         | 설명           |
| ----------- | ------------ | -------------- |
| `isAllDay`  | `Boolean`    | 종일 여부      |
| `startAt`   | `Instant?`   | 시간 일정일 때 |
| `endAt`     | `Instant?`   | 시간 일정일 때 |
| `startDate` | `LocalDate?` | 종일일 때      |
| `endDate`   | `LocalDate?` | 종일 배타적 끝 |

시간 값과 날짜 값을 혼합하지 않는다. 기간의 끝이 시작보다 앞이면 거절한다.

### PendingInboxCompletionDto

| 필드                | 타입                      | 설명                                           |
| ------------------- | ------------------------- | ---------------------------------------------- |
| `itemId`            | `UUID`                    | 정리 대기 원본                                 |
| `baseFieldVersions` | `Map<String, UUID?>`      | 본문·정리 대기 상태 기준                       |
| `action`            | `PendingCompletionAction` | 본문 밖 속성이 없으면 휴지통, 있으면 표시 제거 |

수정 결과의 저장과 정리 대기 원본 처리를 같은 묶음으로 한다. 조회 재시도는 조회 결과를 보내지 않고 원본 처리만 저장한다.

### CollectionCreateSpec

| 필드           | 타입          | 설명            |
| -------------- | ------------- | --------------- |
| `collectionId` | `UUID`        | 기기가 정한 ID  |
| `name`         | `String`      | 공백만 불가     |
| `color`        | `SwatchColor` | 필수            |
| `icon`         | `String`      | 필수, 최대 50자 |

### CollectionDto

| 필드            | 타입          | 설명                       |
| --------------- | ------------- | -------------------------- |
| `id`            | `UUID`        | 컬렉션 ID                  |
| `name`          | `String`      | 권한 확인 후 복호화한 이름 |
| `color`         | `SwatchColor` | 비지 않는 색               |
| `icon`          | `String`      | 아이콘 식별자              |
| `isDefault`     | `Boolean`     | 삭제 불가 기본 컬렉션인지  |
| `deletedAt`     | `Instant?`    | 활성이면 `null`            |
| `purgeAfter`    | `Instant?`    | 물리 삭제 예정             |
| `trashBundleId` | `UUID?`       | 삭제 묶음                  |
| `createdAt`     | `Instant`     | 생성                       |
| `updatedAt`     | `Instant`     | 수정                       |
| `syncSeq`       | `Int64`       | 변경 번호                  |

### TrashBundleDto

| 필드           | 타입              | 설명                         |
| -------------- | ----------------- | ---------------------------- |
| `id`           | `UUID`            | 삭제 묶음 ID                 |
| `bundleType`   | `TrashBundleType` | 단건·반복·컬렉션·여러 아이템 |
| `rootTargetId` | `UUID`            | 대표 대상                    |
| `itemIds`      | `List<UUID>`      | 함께 삭제된 아이템           |
| `collectionId` | `UUID?`           | 컬렉션 삭제일 때             |
| `deletedAt`    | `Instant`         | 서버 삭제 반영 시각          |
| `purgeAfter`   | `Instant`         | 30일 뒤                      |

반복 회차 하나의 EXDATE는 이 목록에 포함하지 않는다. 남은 날은 삭제 예정 시각을 기준으로 FE가 표시한다.

### RestoreResultDto

| 필드                    | 타입         | 설명               |
| ----------------------- | ------------ | ------------------ |
| `writeId`               | `UUID`       | 복구 쓰기 ID       |
| `restoredItemIds`       | `List<UUID>` | 함께 복구한 아이템 |
| `restoredCollectionIds` | `List<UUID>` | 함께 복구한 컬렉션 |
| `latestSyncSeq`         | `Int64`      | 복구 경계 번호     |

### ConflictDto

| 필드             | 타입                         | 설명                                                                                                                  |
| ---------------- | ---------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `id`             | `UUID`                       | 충돌 그룹 식별자 (`(itemId, fieldKey)` 기준 결정적 생성)                                                              |
| `itemId`         | `UUID`                       | 대상 아이템                                                                                                           |
| `fieldKey`       | `String`                     | 부딪친 칸                                                                                                             |
| `kind`           | `ConflictKind`               | 칸 값 충돌 또는 짝 없는 회차                                                                                          |
| `currentValue`   | `JsonValue`                  | 서버가 유지하는 값. `fieldKey`의 타입과 같음                                                                          |
| `currentWriteId` | `UUID?`                      | 현재 칸의 마지막 쓰기                                                                                                 |
| `candidates`     | `List<ConflictCandidateDto>` | 버리지 않고 보관한 미해결 후보 전체                                                                                   |
| `occurrence`     | `OccurrenceRefDto?`          | 회차 관련 충돌                                                                                                        |
| `syncSeq`        | `Int64`                      | 그룹의 모든 후보(풀린 것 포함)와 대상 아이템의 변경 번호 가운데 최대. 그룹이 다 풀리면 이 번호로 `DELETE` 변경을 낸다 |

### ConflictCandidateDto

| 필드                 | 타입          | 설명                       |
| -------------------- | ------------- | -------------------------- |
| `id`                 | `UUID`        | 후보 ID                    |
| `value`              | `JsonValue`   | 원래 칸 타입의 복호화 값   |
| `referenceTargetIds` | `List<UUID>?` | 본문 후보에 묶인 언급 집합 |
| `sourceWriteId`      | `UUID`        | 진 쓰기의 ID               |
| `createdAt`          | `Instant`     | 보관 시각                  |

본문(`BODY`) 후보를 고를 때 후보와 함께 보관된 언급 집합도 언급 연결(`REFERENCE`)에 즉시 반영한다.

### ConflictResolutionDto

| 필드                     | 타입                       | 설명                               |
| ------------------------ | -------------------------- | ---------------------------------- |
| `itemId`                 | `UUID`                     | 충돌 대상                          |
| `fieldKey`               | `String`                   | 대상 칸                            |
| `expectedCurrentWriteId` | `UUID?`                    | 선택 화면에서 본 현재 쓰기         |
| `expectedCandidateIds`   | `List<UUID>`               | 선택 당시 후보 전체 집합           |
| `action`                 | `ConflictResolutionAction` | 유지·선택·새 값·예외 재연결·버리기 |
| `candidateId`            | `UUID?`                    | 후보 선택일 때                     |
| `value`                  | `JsonValue?`               | FE 태그 합집합 등 `CUSTOM`일 때    |
| `referenceTargetIds`     | `List<UUID>?`              | 본문을 선택·새로 정하면 필요       |
| `targetOccurrence`       | `OccurrenceRefDto?`        | 짝 없는 예외 재연결 시             |

### ConflictResolutionResultDto

| 필드                   | 타입         | 설명                           |
| ---------------------- | ------------ | ------------------------------ |
| `writeId`              | `UUID`       | 해결 쓰기 ID                   |
| `resolvedCandidateIds` | `List<UUID>` | 모든 기기에서 표시를 지울 후보 |
| `affectedItemIds`      | `List<UUID>` | 정본 갱신 대상                 |
| `latestSyncSeq`        | `Int64`      | 변경 경계 번호                 |

### UndoResultDto

| 필드                | 타입           | 설명                               |
| ------------------- | -------------- | ---------------------------------- |
| `writeId`           | `UUID`         | 취소 쓰기                          |
| `affectedItemIds`   | `List<UUID>`   | 갱신할 아이템                      |
| `restoredFieldKeys` | `List<String>` | 직전 값 또는 상속 상태로 돌아간 칸 |
| `trashBundleIds`    | `List<UUID>`   | 생성물을 휴지통으로 보낸 결과      |
| `latestSyncSeq`     | `Int64`        | 변경 경계 번호                     |

### SettingsDto

| 필드               | 타입                       | 설명                                                                                                                         |
| ------------------ | -------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `theme`            | `Theme`                    | 기본 `LIGHT`                                                                                                                 |
| `weekStartDay`     | `WeekStartDay`             | `SUNDAY` 또는 `MONDAY`, 기본 `MONDAY`                                                                                        |
| `dayStartHour`     | `Integer`                  | 0~12 정수, 기본 7. 날짜 경계는 여전히 00:00                                                                                  |
| `density`          | `DensityDto`               | 첫 출하 고정값                                                                                                               |
| `shortcuts`        | `List<ShortcutBindingDto>` | 단축키 재정의. 초기 목록 미정                                                                                                |
| `aiGuideline`      | `String?`                  | 암호화 저장한 지침. 길이 상한 미정                                                                                           |
| `aiConsent`        | `Boolean`                  | AI 해석 동의, 기본 `false`                                                                                                   |
| `ocrServerConsent` | `Boolean`                  | 서버 캡처 인식 동의, 기본 `false`                                                                                            |
| `timeZone`         | `String`                   | 계정 기본 시간대(IANA). 새 아이템의 `timeZone`이 이 값을 받는다. 처음 값은 가입한 기기의 시간대(`DeviceContextDto.timeZone`) |
| `defaultAlarms`    | `DefaultAlarmsDto`         | 종류별 기본 알림. 만들 때 알람을 따로 정하지 않은 아이템의 `alarms`에 기기가 채운다                                          |
| `showHolidays`     | `Boolean`                  | 공휴일 표시, 기본 `true`                                                                                                     |
| `syncSeq`          | `Int64`                    | 설정 버전                                                                                                                    |
| `updatedAt`        | `Instant`                  | 수정 시각                                                                                                                    |

### SettingsPatchDto

| 필드               | 타입                        | 설명                                                           |
| ------------------ | --------------------------- | -------------------------------------------------------------- |
| `theme`            | `Theme?`                    | 포함하면 변경, `null`은 불허                                   |
| `weekStartDay`     | `WeekStartDay?`             | 포함하면 변경                                                  |
| `dayStartHour`     | `Integer?`                  | 포함하면 0~12 정시                                             |
| `shortcuts`        | `List<ShortcutBindingDto>?` | 포함한 목록으로 교체                                           |
| `aiGuideline`      | `String?`                   | `null`·빈 문자열은 지침 비우기                                 |
| `aiConsent`        | `Boolean?`                  | 포함하면 동의 변경                                             |
| `ocrServerConsent` | `Boolean?`                  | 포함하면 동의 변경                                             |
| `timeZone`         | `String?`                   | 포함하면 변경, `null`은 불허. 이미 있는 아이템은 바뀌지 않는다 |
| `defaultAlarms`    | `DefaultAlarmsDto?`         | 포함하면 통째로 교체                                           |
| `showHolidays`     | `Boolean?`                  | 포함하면 변경                                                  |

표의 `?`는 일부 변경의 생략 가능성도 표시한다. 명시적 `null` 허용은 각 설명을 따른다. `density`는 첫 출하 읽기 전용이다. 보일 컬렉션은 설정이 아니라 보기 상태(`hiddenCollectionIds`)에 둔다.

### DefaultAlarmsDto

| 필드           | 타입            | 설명                                            |
| -------------- | --------------- | ----------------------------------------------- |
| `timedEvent`   | `List<Integer>` | 시각 일정                                       |
| `allDayEvent`  | `List<Integer>` | 종일 일정. 기준은 그날의 하루 시작 시각         |
| `timedTodo`    | `List<Integer>` | 시각을 고른 할 일                               |
| `dateOnlyTodo` | `List<Integer>` | 날짜만 고른 할 일. 기준은 그날의 하루 시작 시각 |

각 목록은 `ItemFieldsDto.alarms`와 같은 뜻(기준 시각 몇 분 전, 0은 정시)이다. 일정이면서 할 일이면 일정의 값을 쓴다.

### DensityDto

| 필드      | 타입     | 설명                    |
| --------- | -------- | ----------------------- |
| `month`   | `String` | 첫 출하 `COMPACT` 고정  |
| `default` | `String` | 첫 출하 `STANDARD` 고정 |

### ShortcutBindingDto

| 필드        | 타입           | 설명                                     |
| ----------- | -------------- | ---------------------------------------- |
| `commandId` | `String`       | 앱 명령 식별자                           |
| `keyCode`   | `String`       | 물리 키 코드, 예: `KeyZ`                 |
| `modifiers` | `List<String>` | 수정 키 코드 목록. 허용값·충돌 규칙 미정 |

### ViewStateDto

| 필드        | 타입                  | 설명             |
| ----------- | --------------------- | ---------------- |
| `viewKey`   | `String`              | 모듈 보기 식별자 |
| `state`     | `ViewStatePayloadDto` | 해당 보기의 상태 |
| `syncSeq`   | `Int64`               | 보기 버전        |
| `updatedAt` | `Instant`             | 수정 시각        |

### ViewStatePayloadDto

| 필드                  | 타입                | 설명                                                                                                            |
| --------------------- | ------------------- | --------------------------------------------------------------------------------------------------------------- |
| `displayMode`         | `String?`           | 월력·웹 주간 등 등록된 표시 코드                                                                                |
| `sortKey`             | `String?`           | 이름·최근 수정·만든 순·마감·우선순위 등의 코드                                                                  |
| `sortDirection`       | `String?`           | `ASC` 또는 `DESC`                                                                                               |
| `groupKey`            | `String?`           | 보드 그룹 코드                                                                                                  |
| `todoStatuses`        | `List<TodoStatus>?` | 할 일 모듈의 상태 거르기                                                                                        |
| `visibleDays`         | `List<Integer>?`    | 시간표 표시 요일 1~7, 기본 월~금                                                                                |
| `panelOpen`           | `Boolean?`          | 웹 사이드 패널 등 해당 보기 상태                                                                                |
| `panelDate`           | `LocalDate?`        | 패널에서 보는 날짜                                                                                              |
| `hiddenCollectionIds` | `List<UUID>?`       | 끈 컬렉션. `CALENDAR_LAST_VIEW`·`TIMETABLE`에서 각자 갖는다. 빈 목록이면 모두 보여 새 컬렉션은 보이는 채 생긴다 |

모든 필드를 모든 보기에 허용하지 않는다. `viewKey`별 허용 필드·코드 등록표는 미정이다. 검색어·AI 입력 문장은 보기 상태에 넣지 않는다.

### PinDto

| 필드       | 타입      | 설명        |
| ---------- | --------- | ----------- |
| `itemId`   | `UUID`    | 고정 아이템 |
| `pinnedAt` | `Instant` | 고정 시각   |
| `syncSeq`  | `Int64`   | 변경 번호   |

### InputScreenConfigDto

| 필드           | 타입                       | 설명                                        |
| -------------- | -------------------------- | ------------------------------------------- |
| `code`         | `String`                   | 구성 식별자. 고정 Enum으로 막지 않음        |
| `displayOrder` | `Integer`                  | 메뉴 순서                                   |
| `title`        | `String`                   | 시드 표시 이름, 확정 화면 글자는 Figma 기준 |
| `fieldGroups`  | `List<InputFieldGroupDto>` | 한 화면 안의 필드 묶음                      |
| `updatedAt`    | `Instant`                  | 시드 구성 수정                              |

### InputFieldGroupDto

| 필드       | 타입                        | 설명                                             |
| ---------- | --------------------------- | ------------------------------------------------ |
| `groupKey` | `String`                    | 기본·일정·할 일·반복·알람·노트·시간표 등 등록 키 |
| `fields`   | `List<InputFieldConfigDto>` | 묶음에 들어가는 필드                             |

### InputFieldConfigDto

| 필드           | 타입      | 설명                        |
| -------------- | --------- | --------------------------- |
| `fieldKey`     | `String`  | 속성 키                     |
| `valueType`    | `String`  | 렌더링·검증 타입 코드       |
| `required`     | `Boolean` | 해당 입력 구성에서 필수인지 |
| `displayOrder` | `Integer` | 묶음 안 순서                |

기본값 사용자 조립·필드 숨김·입력 화면 마켓은 MVP 밖이다.

### AiContextDto

| 필드              | 타입                  | 설명                                                               |
| ----------------- | --------------------- | ------------------------------------------------------------------ |
| `module`          | `String`              | `HOME`·`CALENDAR`·`TIMETABLE`·`TODO`·`NOTE`·`ARCHIVE` 등 화면 코드 |
| `date`            | `LocalDate?`          | 화면이 날짜 하나를 가리킬 때만                                     |
| `weekStart`       | `LocalDate?`          | 주간 맥락 시작                                                     |
| `weekEnd`         | `LocalDate?`          | 주간 맥락의 배타적 끝                                              |
| `selectionKind`   | `String?`             | 선택함·방금 고침 등 맥락 코드                                      |
| `selectedTargets` | `List<ItemTargetDto>` | 명시적으로 선택한 최소 대상. 없으면 `[]`                           |

보낸 순간에 고정한다. 화면에 보인다는 이유만으로 수정 대상으로 삼지 않는다. '방금 찾은 목록' 자체는 서버·AI로 보내지 않으며, 사용자가 직전 조회 결과에서 후속 변경을 지시할 때만 FE가 매칭된 최소 후보를 `candidates`로 추려 전송한다.

### AiCandidateDto

| 필드                | 타입                 | 설명               |
| ------------------- | -------------------- | ------------------ |
| `target`            | `ItemTargetDto`      | 허용할 대상        |
| `name`              | `String?`            | 후보 이름          |
| `schedule`          | `ScheduleValueDto?`  | 후보의 때          |
| `dueAt`             | `Instant?`           | 시각을 고른 마감   |
| `dueDate`           | `LocalDate?`         | 날짜만 고른 마감   |
| `timeZone`          | `String?`            | 후보의 표준 시간대 |
| `recurrence`        | `RecurrenceSpec?`    | 반복 규칙          |
| `alarms`            | `List<Integer>`      | 알람 목록          |
| `collectionId`      | `UUID`               | 소속               |
| `baseFieldVersions` | `Map<String, UUID?>` | 보낸 당시 버전     |

후보 칸은 명세 4.3의 허용 목록을 따른다. 본문·화면 전체·워크스페이스 전체·조회 결과를 싣지 않는다. 상한은 미정이다.

### AiInterpretationDto

| 필드               | 타입                    | 설명                                           |
| ------------------ | ----------------------- | ---------------------------------------------- |
| `requestId`        | `UUID`                  | 해석 요청 ID                                   |
| `revision`         | `String`                | 해석·답 버전                                   |
| `kind`             | `AiResultKind`          | 검증할 계획·질문·조회 조건·지원 불가·기존 완료 |
| `operations`       | `List<AiOperationDto>`  | 생성·수정·삭제·미룸 제안. 없으면 `[]`          |
| `questions`        | `List<AiQuestionDto>`   | 남은 질문. 있으면 어떤 아이템도 저장하지 않음  |
| `query`            | `AiQueryDto?`           | 조회 조건만. 검색 실행 결과 아님               |
| `warnings`         | `List<WriteWarningDto>` | 기본값·날짜 해석·참조 시점 등의 표시           |
| `reason`           | `String?`               | 지원 불가 이유                                 |
| `committedBatchId` | `UUID?`                 | 이미 저장된 요청이면 기존 묶음 ID              |

`READY`는 저장 완료가 아니다. FE가 대상·버전·날짜·값·범위를 검증한 후 `/batches`를 호출한다. 조회에는 아이템 변경·되돌리기가 없다.

### AiOperationDto

| 필드                            | 타입                         | 설명                                               |
| ------------------------------- | ---------------------------- | -------------------------------------------------- |
| `operationId`                   | `UUID`                       | 계획 안의 연산 ID                                  |
| `kind`                          | `AiOperationKind`            | 만들기·고치기·지우기·미루기                        |
| `target`                        | `ItemTargetDto?`             | 고치기·지우기·미룸 대상                            |
| `scope`                         | `MutationScope?`             | 반복 범위. 모호하면 질문으로 반환                  |
| `effectiveDate`                 | `LocalDate?`                 | 이후 전체 적용 시작일                              |
| `proposedFields`                | `ItemFieldsDto`              | 문장·맥락 근거가 있는 값만. 생략 값 임의 생성 금지 |
| `dateInstructions`              | `List<AiDateInstructionDto>` | FE가 기준 시각으로 확정할 날짜·시간 표현           |
| `timeDependency`                | `TimeDependencyDto?`         | 다른 아이템의 때를 한 번 참조                      |
| `postponeMode`                  | `PostponeMode?`              | 미룸 제안일 때                                     |
| `deleteDerivedCreatedFromItems` | `Boolean`                    | 파생 출처 아이템 함께 삭제 의도가 명확한 경우만    |

### AiDateInstructionDto

| 필드            | 타입      | 설명                                               |
| --------------- | --------- | -------------------------------------------------- |
| `fieldKey`      | `String`  | 시작·마감·적용일 등의 대상 칸                      |
| `expression`    | `String`  | 문장에 있는 상대/절대 날짜·시각 표현               |
| `resolvedValue` | `String?` | 해석된 값 후보. 최종 시간·날짜 타입 검증은 FE가 함 |

날짜 빠진 시각은 맥락이 날짜 하나일 때만 그 날짜로 푼다. 월력·주간 격자는 날짜를 묻는다. 재시도에서는 저장된 기준을 사용하고 이미 지났으면 바로 저장하지 않는다.

### TimeDependencyDto

| 필드                | 타입                 | 설명                          |
| ------------------- | -------------------- | ----------------------------- |
| `referenceTarget`   | `ItemTargetDto`      | 기준 아이템                   |
| `referenceField`    | `String`             | 기준 시작·끝 등의 칸          |
| `offsetMinutes`     | `Integer`            | 기준에 더할 분. 이전이면 음수 |
| `baseFieldVersions` | `Map<String, UUID?>` | 기준 값을 읽은 버전           |

그때의 값으로 한 번 계산하고 출처 연결을 만든다. 기준 아이템이 나중에 바뀌어도 자동으로 따라가지 않는다.

### AiQuestionDto

| 필드         | 타입                | 설명                             |
| ------------ | ------------------- | -------------------------------- |
| `questionId` | `String`            | 요청 안의 질문 식별자            |
| `message`    | `String`            | 짧은 질문                        |
| `choices`    | `List<AiChoiceDto>` | 근거 있는 선택지. 수 상한은 미정 |
| `required`   | `Boolean`           | 해결 전 저장할 수 없는 질문인지  |

대상·범위 질문을 먼저 제시한다. 자유 입력 답은 항상 허용한다. 필수 질문을 건너뛰었다고 기본값을 지어내지 않는다.

### AiChoiceDto

| 필드       | 타입        | 설명                    |
| ---------- | ----------- | ----------------------- |
| `choiceId` | `String`    | 선택지 식별자           |
| `label`    | `String`    | 표시 글자               |
| `reason`   | `String`    | 이 선택지를 제시한 근거 |
| `value`    | `JsonValue` | 구조화된 선택 값        |

### AiAnswerDto

| 필드         | 타입      | 설명         |
| ------------ | --------- | ------------ |
| `questionId` | `String`  | 답할 질문    |
| `choiceId`   | `String?` | 선택지 답    |
| `text`       | `String?` | 자유 입력 답 |

선택지 또는 자유 입력 중 하나 이상을 채운다(선택지를 고르면서 추가 조건을 `text`로 함께 보충하는 것도 허용한다).

### AiQueryDto

| 필드              | 타입                    | 설명                    |
| ----------------- | ----------------------- | ----------------------- |
| `rangeStart`      | `AiDateInstructionDto?` | 찾을 기간 시작          |
| `rangeEnd`        | `AiDateInstructionDto?` | 찾을 기간의 배타적 끝   |
| `natures`         | `List<ItemNature>`      | 속성으로 판정할 성격    |
| `todoStatuses`    | `List<TodoStatus>`      | 상태 조건               |
| `collectionIds`   | `List<UUID>`            | 소속 조건               |
| `nameContains`    | `String?`               | 이름에 든 말            |
| `tagContains`     | `String?`               | 태그에 든 말            |
| `freeTimeMinutes` | `Integer?`              | 필요한 빈 시간 길이, 분 |

FE가 받은 워크스페이스에서 실행한다. 빈 시간은 반복 회차를 포함하며 끝과 시작이 같으면 겹치지 않는다. 종일 일정·끝 없는 표지·취소됨은 빈 시간을 막지 않는다. 23시부터 다음 날 하루 시작 시각까지는 제안에서 제외한다. 찾은 결과를 이 DTO에 덧붙여 서버로 보내지 않는다.

### AiRequestDto

| 필드                | 타입                  | 설명                          |
| ------------------- | --------------------- | ----------------------------- |
| `requestId`         | `UUID`                | 요청 ID                       |
| `sentence`          | `String`              | 처음 문장                     |
| `referenceTime`     | `Instant`             | 원래 기준 시각                |
| `referenceTimeZone` | `String`              | 원래 IANA 시간대              |
| `context`           | `AiContextDto`        | 고정 맥락                     |
| `answers`           | `List<AiAnswerDto>`   | 모은 답                       |
| `interpretation`    | `AiInterpretationDto` | 현재 질문·계획·기존 저장 참조 |
| `createdAt`         | `Instant`             | 생성 시각                     |
| `updatedAt`         | `Instant`             | 마지막 답·해석 시각           |

대기 기록은 로그가 아닌 사용자 대화 데이터다. 암호화·삭제 정책은 대화 기록과 함께 적용한다.

### ConversationSummaryDto

| 필드             | 타입                    | 설명                |
| ---------------- | ----------------------- | ------------------- |
| `id`             | `UUID`                  | 대화 ID             |
| `status`         | `ConversationStatus`    | 기록 상태           |
| `sentence`       | `String`                | 당시 보낸 문장      |
| `recordDate`     | `LocalDate`             | 기기 기준 기록 날짜 |
| `counts`         | `ConversationCountsDto` | 저장 변경 요약 수   |
| `relatedBatchId` | `UUID?`                 | 연결된 저장 묶음    |
| `requestId`      | `UUID?`                 | 이어 답할 해석 요청 |
| `createdAt`      | `Instant`               | 최근순 목록 기준    |

조회 결과(`QUERY_RESULT`)의 찾은 아이템 목록은 서버에 저장하지 않는다. 조회 기록을 다시 열 때는 저장된 `query` 조건으로 FE가 로컬 워크스페이스에서 다시 계산한다.

### ConversationCountsDto

| 필드                   | 타입      | 설명         |
| ---------------------- | --------- | ------------ |
| `createdScheduleCount` | `Integer` | 새 일정 수   |
| `createdTodoCount`     | `Integer` | 새 할 일 수  |
| `updatedCount`         | `Integer` | 고친 결과 수 |
| `deletedCount`         | `Integer` | 지운 결과 수 |

같은 아이템이 일정·할 일이면 두 성격의 표시 수에 모두 포함될 수 있다. 결과 개수와 아이템 수를 FE가 구분한다.

### ConversationRecordDto

| 필드                | 타입                             | 설명                                      |
| ------------------- | -------------------------------- | ----------------------------------------- |
| `status`            | `ConversationStatus`             | 기록 상태                                 |
| `sentence`          | `String`                         | 보낸 문장                                 |
| `referenceTime`     | `Instant`                        | 요청 기준 시각                            |
| `referenceTimeZone` | `String`                         | 요청 기준 시간대                          |
| `recordDate`        | `LocalDate`                      | 날짜별 묶음 날짜                          |
| `requestId`         | `UUID?`                          | 해석·되묻기 요청                          |
| `context`           | `AiContextDto`                   | 처음 요청 맥락                            |
| `questions`         | `List<AiQuestionDto>`            | 당시 질문                                 |
| `answers`           | `List<AiAnswerDto>`              | 모은 답                                   |
| `resultRefs`        | `List<ConversationResultRefDto>` | 실제로 저장된 결과 참조                   |
| `query`             | `AiQueryDto?`                    | 조회 조건. 찾은 항목·개수는 전송하지 않음 |
| `counts`            | `ConversationCountsDto`          | 변경 결과 표시 수                         |

### ConversationResultRefDto

| 필드       | 타입      | 설명                               |
| ---------- | --------- | ---------------------------------- |
| `batchId`  | `UUID`    | 실제 저장 묶음                     |
| `resultId` | `UUID`    | 그 묶음의 결과 하나                |
| `summary`  | `String?` | FE가 정리한 변경 요약, 암호화 저장 |

조회 결과를 이 목록으로 우회 저장하지 않는다.

### ConversationDto

| 필드        | 타입                    | 설명                         |
| ----------- | ----------------------- | ---------------------------- |
| `id`        | `UUID`                  | 기록 ID                      |
| `revision`  | `String`                | 동시 수정용 불투명 버전      |
| `record`    | `ConversationRecordDto` | 암호화 저장 후 복호화한 기록 |
| `createdAt` | `Instant`               | 생성 시각                    |
| `updatedAt` | `Instant`               | 수정 시각                    |

### TimetableRecognitionDto

| 필드       | 타입                               | 설명                               |
| ---------- | ---------------------------------- | ---------------------------------- |
| `items`    | `List<RecognizedTimetableItemDto>` | 묶음 후보. 실제 저장된 아이템 아님 |
| `warnings` | `List<WriteWarningDto>`            | 읽지 못한 칸 등                    |
| `engine`   | `String?`                          | 사용한 인식 엔진 식별자. 선정 미정 |

### RecognizedTimetableItemDto

| 필드          | 타입                      | 설명                             |
| ------------- | ------------------------- | -------------------------------- |
| `candidateId` | `String`                  | 보정 격자의 임시 ID              |
| `name`        | `String?`                 | 인식 이름                        |
| `location`    | `String?`                 | 인식 장소                        |
| `slots`       | `List<RecognizedSlotDto>` | 같은 아이템으로 묶은 시간대 후보 |

인식되지 않은 값은 `null`로 반환한다. FE가 새 아이템 ID와 시간대 ID를 만들고 필수 값을 보정한다.

### RecognizedSlotDto

| 필드        | 타입         | 설명               |
| ----------- | ------------ | ------------------ |
| `dayOfWeek` | `Integer?`   | 1~7, 모르면 `null` |
| `startTime` | `LocalTime?` | 인식한 시작        |
| `endTime`   | `LocalTime?` | 인식한 끝          |

### IcsImportRecordDto

| 필드                   | 타입             | 설명                                                  |
| ---------------------- | ---------------- | ----------------------------------------------------- |
| `id`                   | `UUID`           | 가져오기 기준 기록 ID                                 |
| `externalUid`          | `String`         | iCalendar UID, 최대 500자                             |
| `itemId`               | `UUID`           | 기존 아이템                                           |
| `lastImportedSnapshot` | `IcsSnapshotDto` | FE의 다음 필드별 비교 기준. 서버는 글을 비교하지 않음 |
| `updatedAt`            | `Instant`        | 마지막 가져오기                                       |

### IcsSnapshotDto

| 필드         | 타입                      | 설명                    |
| ------------ | ------------------------- | ----------------------- |
| `fields`     | `ItemFieldsDto`           | 마지막 파일의 가져올 값 |
| `exceptions` | `List<IcsOccurrenceSpec>` | 파일의 회차 예외·삭제   |

### IcsOccurrenceSpec

| 필드                  | 타입               | 설명                        |
| --------------------- | ------------------ | --------------------------- |
| `occurrence`          | `OccurrenceRefDto` | 원래 회차                   |
| `isDeletedOccurrence` | `Boolean`          | EXDATE·삭제로 해석한 회차   |
| `changedFields`       | `ItemFieldsDto`    | RECURRENCE-ID의 수정한 칸만 |

파일의 `STATUS:CANCELLED`는 원래 해당 회차가 취소됨이면 취소됨, 그 외에는 회차 삭제로 FE가 변환한다.

### IcsImportEntryDto

| 필드                      | 타입                      | 설명                                         |
| ------------------------- | ------------------------- | -------------------------------------------- |
| `externalUid`             | `String`                  | 파일 UID                                     |
| `recordId`                | `UUID?`                   | 기존 가져오기 기록이면 그 ID                 |
| `ignoreUidMatching`       | `Boolean`                 | 분리 내보낸 시간표의 새 가져오기일 때 `true` |
| `create`                  | `ItemCreateSpec?`         | 새 아이템일 때                               |
| `patch`                   | `ItemPatchSpec?`          | 기존 UID 갱신일 때                           |
| `exceptions`              | `List<IcsOccurrenceSpec>` | 적용할 예외                                  |
| `lastImportedSnapshot`    | `IcsSnapshotDto`          | 이번 파일의 기준값                           |
| `preservedFieldKeys`      | `List<String>`            | FE가 사용자 수정으로 보호한 칸               |
| `preservedOccurrenceKeys` | `List<String>`            | 파일에서 사라져도 보호할 손댄 회차           |

`create`와 `patch` 중 하나만 보낸다. 보존한 칸을 수정 계획에 동시에 넣지 않는다. 같은 UID의 소속은 유지한다.

### SystemNotificationDto

| 필드               | 타입                           | 설명         |
| ------------------ | ------------------------------ | ------------ |
| `id`               | `UUID`                         | 서버 안내 ID |
| `notificationType` | `SystemNotificationType`       | 안내 종류    |
| `payload`          | `SystemNotificationPayloadDto` | 안내 내용    |
| `createdAt`        | `Instant`                      | 발생 시각    |
| `syncSeq`          | `Int64`                        | 변경 번호    |

### SystemNotificationPayloadDto

| 필드            | 타입      | 설명                         |
| --------------- | --------- | ---------------------------- |
| `message`       | `String`  | 표시할 안내                  |
| `itemId`        | `UUID?`   | 삭제된 대상 관련이면 대상 ID |
| `writeId`       | `UUID?`   | 관련 쓰기                    |
| `trashBundleId` | `UUID?`   | 복구할 묶음                  |
| `noticeCode`    | `String?` | 서버 안내 식별자             |

---

## Enum 정의

### Platform

| 값       | 설명     |
| -------- | -------- |
| `IOS`    | iPhone   |
| `IPADOS` | iPad     |
| `WEB`    | 브라우저 |

### OAuthProvider

| 값       | 설명  |
| -------- | ----- |
| `GOOGLE` | 구글  |
| `APPLE`  | Apple |

### AuthNextStep

| 값                         | 설명                                       |
| -------------------------- | ------------------------------------------ |
| `MAIL_CODE_REQUIRED`       | 같은 세션에서 코드 입력                    |
| `AUTHENTICATED`            | 세션 발급·워크스페이스 받기                |
| `RECOVERY_CHOICE_REQUIRED` | 코드 확인된 탈퇴 유예 계정 선택            |
| `PASSWORD_RESET_COMPLETED` | 모든 로그인 종료·새 비밀번호로 다시 로그인 |

### RecoveryAction

| 값        | 설명                                |
| --------- | ----------------------------------- |
| `RESTORE` | 옛 계정 되살리기                    |
| `RESTART` | 옛 계정 즉시 물리 삭제·새 계정 시작 |

### SessionEndReason

| 값                | 설명                     |
| ----------------- | ------------------------ |
| `EXPIRED`         | 일반 세션 만료           |
| `PASSWORD_RESET`  | 비밀번호 재설정으로 종료 |
| `DEVICE_REVOKED`  | 기기 목록에서 끊음       |
| `NOT_ME`          | 내가 아니다로 종료       |
| `ACCOUNT_DELETED` | 탈퇴                     |
| `UNKNOWN`         | 원인 확인 불가           |

`EXPIRED`는 iOS 사본·대기 쓰기 유지, `PASSWORD_RESET`은 사본 삭제·대기 쓰기 봉인, 기기 끊기·내가 아니다·탈퇴는 둘 다 삭제한다. 다른 계정 로그인 때의 봉인 규칙은 F-44를 따른다.

### SwatchColor

| 값       | 설명   |
| -------- | ------ |
| `RED`    | 빨강   |
| `ORANGE` | 주황   |
| `AMBER`  | 호박색 |
| `GREEN`  | 초록   |
| `TEAL`   | 청록   |
| `BLUE`   | 파랑   |
| `INDIGO` | 남색   |
| `PINK`   | 분홍   |
| `GRAY`   | 회색   |

대문자를 JSON 값으로 사용한다. 기능 명세의 소문자 스와치명과 1:1 대응하며 hex를 저장하지 않는다.

### TodoStatus

| 값          | 설명   |
| ----------- | ------ |
| `TODO`      | 미완료 |
| `DONE`      | 완료   |
| `POSTPONED` | 미룸   |
| `CANCELLED` | 취소됨 |

### LinkType

| 값             | 설명               |
| -------------- | ------------------ |
| `CREATED_FROM` | 출처               |
| `REFERENCE`    | 본문 위키링크 언급 |
| `DERIVED_FROM` | 마스터·회차 예외   |
| `POSTPONED_TO` | 원본·이월본        |
| `SPLIT_FROM`   | 옛·새 마스터 나뉨  |

### MutationScope

| 값                | 설명                      |
| ----------------- | ------------------------- |
| `SINGLE`          | 일반 아이템 하나          |
| `THIS_OCCURRENCE` | 반복 회차 하나            |
| `THIS_AND_FUTURE` | 적용 시작일부터 이후 전체 |
| `ALL`             | 나뉜 사슬을 포함한 전체   |

### BatchType

| 값                 | 설명                                  |
| ------------------ | ------------------------------------- |
| `AI_MUTATION`      | 한 문장의 AI 변경 결과                |
| `TIMETABLE_CREATE` | 새 컬렉션 선택을 포함한 시간표 만들기 |
| `TODO_POSTPONE`    | 원본·이월본·연결 미룸                 |
| `ICS_IMPORT`       | ICS 가져오기                          |
| `BULK_DELETE`      | 아이템 대량 삭제                      |

### BatchStatus

| 값          | 설명                                                                                                                                        |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `COMPLETED` | 원자 저장 완료                                                                                                                              |
| `FAILED`    | 전체 미반영으로 실패 확정                                                                                                                   |
| `UNDONE`    | 묶음의 결과를 되돌린 상태 (되돌린 뒤에도 이미 완료된 `aiRequestId`는 재실행하지 않으며, 대화 기록이 삭제된 결과는 되돌리기를 허용하지 않음) |

기록 없는 `404`를 `FAILED`로 해석하지 않는다.

### PostponeMode

| 값             | 설명                         |
| -------------- | ---------------------------- |
| `TO_DATE`      | 고른 날에 이월본 생성        |
| `SKIP_TO_NEXT` | 원본 회차 취소됨·이월본 없음 |

### PendingCompletionAction

| 값                   | 설명                                     |
| -------------------- | ---------------------------------------- |
| `TRASH_ITEM`         | 정리 대기 원본을 휴지통으로              |
| `CLEAR_PENDING_FLAG` | 추가 속성이 있는 원본은 두고 표시만 제거 |

### TrashBundleType

| 값                      | 설명                  |
| ----------------------- | --------------------- |
| `SINGLE_ITEM`           | 아이템 하나           |
| `RECURRENCE_SERIES`     | 반복 마스터·예외·사슬 |
| `COLLECTION_WITH_ITEMS` | 컬렉션과 소속 아이템  |
| `BULK_ITEMS`            | 함께 고른 여러 아이템 |

### ConflictKind

| 값                    | 설명                         |
| --------------------- | ---------------------------- |
| `FIELD_VALUE`         | 같은 칸의 후보 충돌          |
| `ORPHANED_OCCURRENCE` | 짝이 없거나 자리가 겹친 예외 |

### ConflictResolutionAction

| 값                   | 설명                            |
| -------------------- | ------------------------------- |
| `KEEP_CURRENT`       | 서버 값을 유지하고 본 후보 해결 |
| `SELECT_CANDIDATE`   | 보존 후보 선택                  |
| `CUSTOM`             | FE에서 만든 합집합 등 새 값     |
| `REATTACH_EXCEPTION` | 예외를 고른 회차에 연결         |
| `DISCARD_EXCEPTION`  | 짝 없는 예외를 소프트 삭제      |

### Theme

| 값      | 설명   |
| ------- | ------ |
| `LIGHT` | 라이트 |
| `DARK`  | 다크   |

### WeekStartDay

| 값       | 설명        |
| -------- | ----------- |
| `SUNDAY` | 일요일 시작 |
| `MONDAY` | 월요일 시작 |

### AiResultKind

| 값            | 설명                         |
| ------------- | ---------------------------- |
| `READY`       | FE 검증·저장을 기다리는 계획 |
| `ASK`         | 추가 답 필요                 |
| `QUERY`       | FE가 실행할 조회 조건        |
| `UNSUPPORTED` | 지원하지 않는 요청           |
| `COMPLETED`   | 이미 저장된 요청의 결과      |

### AiOperationKind

| 값         | 설명   |
| ---------- | ------ |
| `CREATE`   | 만들기 |
| `PATCH`    | 고치기 |
| `DELETE`   | 지우기 |
| `POSTPONE` | 미루기 |

### ItemNature

| 값          | 설명                    |
| ----------- | ----------------------- |
| `SCHEDULE`  | 일정 속성이 있는 모습   |
| `TODO`      | 할 일 속성이 있는 모습  |
| `NOTE`      | 노트 속성이 있는 모습   |
| `TIMETABLE` | 시간표 속성이 있는 모습 |

조회 조건·표시용이다. 아이템 종류로 저장하지 않는다.

### ConversationStatus

| 값                | 설명                |
| ----------------- | ------------------- |
| `SAVED`           | 변경 저장 완료      |
| `QUERY_RESULT`    | 조회 문장·조건 기록 |
| `AWAITING_ANSWER` | 질문 답 대기        |
| `PENDING_INBOX`   | 정리 대기           |

### SystemNotificationType

| 값                      | 설명                        |
| ----------------------- | --------------------------- |
| `SERVER_NOTICE`         | 서버 안내                   |
| `WRITE_ON_DELETED_ITEM` | 휴지통 대상에 쓰기가 반영됨 |

### ChangeOperation

| 값           | 설명                          |
| ------------ | ----------------------------- |
| `UPSERT`     | 정본 추가·갱신                |
| `DELETE`     | 자원·연결·고정·기록 제거      |
| `INVALIDATE` | 대화 등 별도 조회 캐시 무효화 |

### WorkspaceResourceType

| 값                     | 설명                                                   |
| ---------------------- | ------------------------------------------------------ |
| `ITEM`                 | 아이템                                                 |
| `COLLECTION`           | 컬렉션                                                 |
| `LINK`                 | 연결                                                   |
| `OCCURRENCE_EXCEPTION` | 회차 예외                                              |
| `CONFLICT`             | 충돌                                                   |
| `SETTINGS`             | 설정                                                   |
| `VIEW_STATE`           | 보기 상태                                              |
| `PIN`                  | 고정                                                   |
| `INPUT_SCREEN_CONFIG`  | 입력 구성. 스냅샷에만 싣고 증분 변경으로는 내지 않는다 |
| `TRASH_BUNDLE`         | 휴지통 묶음                                            |
| `ICS_IMPORT_RECORD`    | 가져오기 비교 기준                                     |
| `SYSTEM_NOTIFICATION`  | 서버 안내                                              |
| `CONVERSATION`         | 대화 ID·무효화·삭제 신호만                             |

---

# 이전 문서

API 명세 합의 v1.0 (11-10)

### 변경 사항 정리

API 명세 합의 변경 기록 v1.0 → v1.1 (11-12)
