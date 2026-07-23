# Collection API 테스트 (`/api/v1/collections`)

모든 엔드포인트는 **인증 필요**(세션 쿠키). 컬렉션 생성 시 요청한 사용자가 `collection_users`에 `role=owner`로
자동 등록되며, `{id}` 하위 엔드포인트는 모두 **해당 컬렉션의 멤버(collection_users에 등록된 사용자)만** 접근할 수 있다.
수정·삭제·멤버 추가/제거는 **owner만** 가능하다.
먼저 [auth.md](auth.md)의 로그인으로 `cookies.txt`를 만든 뒤 `-b cookies.txt`로 호출한다.

- `id`는 UUID
- `kind`는 `calendar` / `notebook` / `semester` 중 하나 (대소문자 무관하게 입력 가능, 응답은 소문자)
- `role`은 `owner` / `editor` / `viewer` 중 하나
- `system_purpose`는 시스템이 자동 생성한 컬렉션 식별용 필드 (`default_calendar` / `todo_calendar` / `default_notebook`).
  일반 사용자 컬렉션은 `null`이며, 이 문서의 엔드포인트로는 설정할 수 없다 (가입 시 백엔드가 자동 INSERT — ADR-024 참고).
- `attributes`는 임의의 JSON 객체(JSONB)
- 인증 없이 호출하면 `401`, 멤버가 아니거나 존재하지 않는 리소스는 `404`, owner가 아닌 사용자의 쓰기 요청은 `403`

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
  "attributes": { "semester": "2026-2" }
}
```

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X POST "http://localhost:8080/api/v1/collections" `
  -H "Content-Type: application/json" `
  -d '{\"kind\":\"calendar\",\"name\":\"2026-2학기 시간표\",\"color\":\"#FF6B6B\",\"icon\":\"calendar-icon\",\"attributes\":{\"semester\":\"2026-2\"}}'
```

---

## 2. 목록 조회 — `GET /api/v1/collections`

- 내가 속한 컬렉션만 반환. `kind` 쿼리로 필터링 가능 (생략 시 전체)
- `owner=me`는 "내 컬렉션만" 조회를 명시하는 파라미터로, 현재는 인증된 사용자 기준으로 항상 동일하게 동작한다
- 성공: `200 OK` + 배열

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt "http://localhost:8080/api/v1/collections?kind=calendar&owner=me"
```

---

## 3. 단건 조회 — `GET /api/v1/collections/{id}`

- 멤버(owner/editor/viewer 누구나)면 조회 가능
- 성공: `200 OK` / 멤버가 아니거나 존재하지 않으면 `404`

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt "http://localhost:8080/api/v1/collections/3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
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

## 4. 수정 — `PATCH /api/v1/collections/{id}`

- **owner만** 가능 (그 외 역할은 `403`)
- 부분 수정: body에 넣은 필드만 반영, `attributes`는 통째 교체가 아니라 기존 값에 병합(merge)
- `kind=semester`인 컬렉션에서 `attributes.start_date`/`end_date`를 바꾸면 파생 인스턴스 재생성이 필요할 수 있으나,
  이번 구현 범위에서는 재생성 로직 없이 값만 반영된다 (후속 작업)
- 성공: `200 OK` + 수정된 컬렉션

### Request Body (일부 필드만 전달 가능)
```json
{
  "name": "2026-2학기 시간표 (수정)",
  "attributes": { "start_date": "2026-09-01" }
}
```

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X PATCH "http://localhost:8080/api/v1/collections/3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f" `
  -H "Content-Type: application/json" `
  -d '{\"name\":\"2026-2학기 시간표 (수정)\",\"attributes\":{\"start_date\":\"2026-09-01\"}}'
```

---

## 5. 삭제 — `DELETE /api/v1/collections/{id}`

- **owner만** 가능 (그 외 역할은 `403`)
- soft delete (`deleted_at` 설정) + 소속 `collection_users` 하드 삭제
- ⚠️ 소속 items(및 `kind=semester`면 `derived_from` 인스턴스) cascade는 **이번 범위에서 미구현**.
  현재 Item 엔티티에 `collection_id` 컬럼이 없어 items ↔ collections 연결 자체가 불가능하다.
  Item에 `collection_id`를 추가하는 후속 작업 이후 여기서 함께 처리해야 한다.
- 성공: `204 No Content`

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X DELETE "http://localhost:8080/api/v1/collections/3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
```

---

## 6. 소속 사용자 조회 — `GET /api/v1/collections/{id}/users`

- 멤버면 조회 가능
- `role`, `systemPurpose`, `joinedAt` 반환
- 성공: `200 OK` + 배열

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt "http://localhost:8080/api/v1/collections/3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f/users"
```

### 응답 예시
```json
[
  {
    "userId": 1,
    "role": "owner",
    "systemPurpose": null,
    "joinedAt": "2026-07-10T01:55:00"
  }
]
```

---

## 7. 사용자 추가 — `POST /api/v1/collections/{id}/users`

- **owner만** 가능 (그 외 역할은 `403`)
- MVP 스펙상 공유 기능은 후기 반영 예정이지만, 엔드포인트 자체는 구현되어 있다
- `role` 생략 시 기본값 `editor`. 이미 멤버인 사용자를 다시 추가하거나 존재하지 않는 `userId`를 지정하면 각각 `400`/`404`
- 성공: `201 Created`

### Request Body
```json
{
  "userId": 2,
  "role": "editor"
}
```

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X POST "http://localhost:8080/api/v1/collections/3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f/users" `
  -H "Content-Type: application/json" `
  -d '{\"userId\":2,\"role\":\"editor\"}'
```

---

## 8. 사용자 제거 — `DELETE /api/v1/collections/{id}/users/{user_id}`

- **owner만** 가능 (그 외 역할은 `403`)
- 대상이 해당 컬렉션의 마지막 남은 owner이면 `400` (컬렉션 소유자 없는 상태 방지)
- 성공: `204 No Content`

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X DELETE "http://localhost:8080/api/v1/collections/3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f/users/2"
```
