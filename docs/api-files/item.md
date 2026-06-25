# Item API 테스트 (`/api/v1/items`)

모든 엔드포인트는 **인증 필요**(세션 쿠키). `owner_id`는 요청 본문이 아니라 **로그인 세션에서 자동 지정**되므로 body에 넣지 않는다.
먼저 [auth.md](auth.md)의 로그인으로 `cookies.txt`를 만든 뒤 `-b cookies.txt`로 호출한다.

- `id`는 UUID
- `attributes`는 임의의 JSON 객체(JSONB)
- 인증 없이 호출하면 `401`, 타인 소유/미존재 리소스 접근은 `404`

---

## 1. 생성 — `POST /api/v1/items`

- 성공: `201 Created` + 생성된 아이템

### Request Body
```json
{
  "name": "노트북",
  "attributes": {
    "brand": "Dell",
    "ram": 32,
    "tags": ["work", "portable"]
  }
}
```

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X POST "http://localhost:8080/api/v1/items" `
  -H "Content-Type: application/json" `
  -d '{\"name\":\"노트북\",\"attributes\":{\"brand\":\"Dell\",\"ram\":32,\"tags\":[\"work\",\"portable\"]}}'
```

### 응답 예시
```json
{
  "id": "3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f",
  "name": "노트북",
  "ownerId": 1,
  "attributes": { "brand": "Dell", "ram": 32, "tags": ["work", "portable"] },
  "createdAt": "2026-06-24T01:55:00",
  "updatedAt": "2026-06-24T01:55:00"
}
```

---

## 2. 목록 조회 — `GET /api/v1/items`

- 내 소유 아이템만 반환
- 성공: `200 OK` + 배열

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt "http://localhost:8080/api/v1/items"
```

---

## 3. 단건 조회 — `GET /api/v1/items/{id}`

- 성공: `200 OK` / 미존재·타인 소유: `404`

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt "http://localhost:8080/api/v1/items/3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
```

---

## 4. 수정 — `PUT /api/v1/items/{id}`

- 전체 교체 방식 (name, attributes 모두 전달)
- 성공: `200 OK` + 수정된 아이템

### Request Body
```json
{
  "name": "노트북 (업무용)",
  "attributes": {
    "brand": "Dell",
    "ram": 64,
    "tags": ["work"]
  }
}
```

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X PUT "http://localhost:8080/api/v1/items/3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f" `
  -H "Content-Type: application/json" `
  -d '{\"name\":\"노트북 (업무용)\",\"attributes\":{\"brand\":\"Dell\",\"ram\":64,\"tags\":[\"work\"]}}'
```

---

## 5. 삭제 — `DELETE /api/v1/items/{id}`

- soft delete (`deleted_at` 설정). 삭제 후 조회하면 `404`
- 성공: `204 No Content`

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X DELETE "http://localhost:8080/api/v1/items/3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
```
