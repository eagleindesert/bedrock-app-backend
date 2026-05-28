# Bedrock App Backend API 명세서

본 문서는 Bedrock App Backend 애플리케이션의 REST API 엔드포인트 목록 및 상세 사양을 정의합니다.

## 목차
1. [인증 API (Authentication API)](#1-인증-api)
   - [회원가입](#post-apiauthsignup)
   - [로그인](#post-apiauthlogin)
   - [로그아웃](#post-apiauthlogout)
   - [회원탈퇴](#delete-apiauthwithdraw)
2. [디버그 API (Debug API)](#2-디버그-api)
   - [디버그 메시지 저장](#post-apidebug)
   - [모든 디버그 메시지 조회](#get-apidebug)
3. [공통 에러 응답 (Global Error Responses)](#3-공통-에러-응답)

---

## 1. 인증 API

기본 URL 경로: `/api/auth`

### `POST /api/auth/signup`
새로운 사용자 계정을 생성합니다.

#### 요청 헤더
* `Content-Type: application/json`

#### 요청 본문 (Request Body)
```json
{
  "email": "user@example.com",
  "password": "password123",
  "nickname": "my_nickname"
}
```
* **유효성 검증 제약 조건**:
  * `email`: 필수 입력값입니다. 올바른 이메일 형식이어야 합니다.
  * `password`: 필수 입력값입니다.
  * `nickname`: 필수 입력값입니다.

#### 응답 (Response)
* **`200 OK`**: 회원가입 성공.
* **`400 Bad Request`**: 유효성 검증 실패(예: 잘못된 이메일 형식) 또는 이미 가입된 이메일입니다.

---

### `POST /api/auth/login`
사용자를 인증하고 세션을 생성합니다. 인증 성공 시 `SESSION` 쿠키가 발급됩니다.

#### 요청 헤더
* `Content-Type: application/json`

#### 요청 본문 (Request Body)
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```
* **유효성 검증 제약 조건**:
  * `email`: 필수 입력값입니다. 올바른 이메일 형식이어야 합니다.
  * `password`: 필수 입력값입니다.

#### 응답 헤더
* `Set-Cookie: SESSION=<session-id>; Path=/; HttpOnly; SameSite=Lax` *(참고: `Secure` 플래그 설정은 로컬 및 운영 환경 프로필 설정에 따라 다르게 적용됩니다)*

#### 응답 (Response)
* **`200 OK`**: 로그인 성공.
* **`401 Unauthorized`**: 이메일 또는 비밀번호가 일치하지 않습니다.
* **`400 Bad Request`**: 유효성 검증 실패.

---

### `POST /api/auth/logout`
현재 활성화된 세션을 무효화하고 보안 컨텍스트를 초기화합니다.

#### 요청 헤더
* 인증 세션 쿠키 필수: `Cookie: SESSION=<session-id>`

#### 응답 (Response)
* **`200 OK`**: 로그아웃 성공.
* **`401 Unauthorized`**: 세션 쿠키가 없거나 유효하지 않습니다.

---

### `DELETE /api/auth/withdraw`
인증된 사용자의 계정을 소프트 딜리트(Soft Delete)하고 즉시 세션을 무효화하여 로그아웃 처리합니다.

#### 요청 헤더
* 인증 세션 쿠키 필수: `Cookie: SESSION=<session-id>`

#### 응답 (Response)
* **`200 OK`**: 회원탈퇴 성공.
* **`401 Unauthorized`**: 세션 쿠키가 없거나 유효하지 않습니다.

---

## 2. 디버그 API

기본 URL 경로: `/api/debug` (인증된 세션 필요)

### `POST /api/debug`
디버그 메시지를 데이터베이스에 저장합니다.

#### 요청 헤더
* 인증 세션 쿠키 필수: `Cookie: SESSION=<session-id>`

#### 쿼리 파라미터 (Query Parameters)
| 파라미터 | 타입 | 필수 여부 | 설명 |
| :--- | :--- | :--- | :--- |
| `message` | String | Yes | 저장할 디버그 메시지 내용 |

#### 응답 본문 (Response Body)
```json
{
  "id": 1,
  "message": "test message",
  "createdAt": "2026-05-29T02:38:45"
}
```

#### 응답 (Response)
* **`200 OK`**: 디버그 메시지 저장 성공.
* **`401 Unauthorized`**: 세션이 유효하지 않거나 존재하지 않습니다.

---

### `GET /api/debug`
저장된 모든 디버그 메시지를 조회합니다.

#### 요청 헤더
* 인증 세션 쿠키 필수: `Cookie: SESSION=<session-id>`

#### 응답 본문 (Response Body)
```json
[
  {
    "id": 1,
    "message": "test message",
    "createdAt": "2026-05-29T02:38:45"
  }
]
```

#### 응답 (Response)
* **`200 OK`**: 디버그 메시지 목록 조회 성공.
* **`401 Unauthorized`**: 세션이 유효하지 않거나 존재하지 않습니다.

---

## 3. 공통 에러 응답

애플리케이션에서 에러가 발생하면 Spring Security 및 Spring Boot 기본 에러 매핑에 의해 다음과 같은 표준 형식으로 응답합니다:

### `401 Unauthorized`
인증이 필요한 엔드포인트에 유효한 `SESSION` 쿠키 없이 요청을 보냈을 때 반환됩니다.
```json
{
  "timestamp": "2026-05-29T02:38:45.123+00:00",
  "status": 401,
  "error": "Unauthorized",
  "path": "/api/debug"
}
```

### `400 Bad Request` (유효성 검증 에러)
요청 파라미터나 본문의 필드에 대한 유효성 검증(Validation)이 실패했을 때 반환됩니다.
```json
{
  "timestamp": "2026-05-29T02:38:45.123+00:00",
  "status": 400,
  "error": "Bad Request",
  "path": "/api/auth/signup"
}
```
