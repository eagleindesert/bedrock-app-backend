# Collection API 테스트 (`/api/v1/collections`)

> 요청/응답 스키마의 최신 기준은 Swagger UI(`http://localhost:8080/swagger-ui.html`)입니다.

모든 엔드포인트는 **인증 필요**(세션 쿠키). 컬렉션 생성 시 요청한 사용자가 `collection_users`에 `role=owner`로 자동 등록되며,
목록 조회는 **내가 속한(collection_users에 내가 등록된) 컬렉션만** 반환한다.
먼저 [auth.md](auth.md)의 로그인으로 `cookies.txt`를 만든 뒤 `-b cookies.txt`로 호출한다.

- `id`는 UUID
- `kind`는 `calendar` / `notebook` / `semester` 중 하나 (대소문자 무관하게 입력 가능, 응답은 소문자)
- `attributes`는 임의의 JSON 객체(JSONB)
- 인증 없이 호출하면 `401`, 존재하지 않는 kind 값이면 `400`

---

## 1. 생성 — `POST /api/v1/collections`

- 성공: `201 Created` + 생성된 컬렉션 (요청자는 `role=owner`로 `collection_users`에 자동 INSERT)

### Request Body
```json
{
  "kind": "calendar",
  "name": "2026-2학기 시간표",
  "color": "#FF6B6B",
  "icon": "calendar-icon",
  "attributes": {
    "semester": "2026-2"
  }
}
```

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X POST "http://localhost:8080/api/v1/collections" `
  -H "Content-Type: application/json" `
  -d '{\"kind\":\"calendar\",\"name\":\"2026-2학기 시간표\",\"color\":\"#FF6B6B\",\"icon\":\"calendar-icon\",\"attributes\":{\"semester\":\"2026-2\"}}'
```

### 응답 예시
```json
{
  "id": "3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f",
  "kind": "calendar",
  "name": "2026-2학기 시간표",
  "color": "#FF6B6B",
  "icon": "calendar-icon",
  "attributes": { "semester": "2026-2" },
  "role": "owner",
  "createdAt": "2026-07-10T01:55:00",
  "updatedAt": "2026-07-10T01:55:00"
}
```

---

## 2. 목록 조회 — `GET /api/v1/collections`

- 내가 속한 컬렉션만 반환 (owner뿐 아니라 향후 editor/viewer로 초대된 컬렉션 포함)
- `kind` 쿼리 파라미터로 필터링 가능 (생략 시 전체)
- `owner=me`는 "내 컬렉션만" 조회를 명시하는 파라미터로, 현재는 인증된 사용자 기준으로 항상 동일하게 동작한다
- 성공: `200 OK` + 배열

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt "http://localhost:8080/api/v1/collections?kind=calendar&owner=me"
```

### 응답 예시
```json
[
  {
    "id": "3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f",
    "kind": "calendar",
    "name": "2026-2학기 시간표",
    "color": "#FF6B6B",
    "icon": "calendar-icon",
    "attributes": { "semester": "2026-2" },
    "role": "owner",
    "createdAt": "2026-07-10T01:55:00",
    "updatedAt": "2026-07-10T01:55:00"
  }
]
```
