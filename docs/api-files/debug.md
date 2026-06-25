# Debug API 테스트 (`/api/debug`)

DB 연결 확인용 엔드포인트. **인증 필요**(세션 쿠키) — `anyRequest().authenticated()` 규칙 적용.

> 주의: 생성 엔드포인트는 JSON body가 아니라 **쿼리 파라미터 `message`**를 받는다 (`@RequestParam String message`).

---

## 1. 메시지 저장 — `POST /api/debug?message=...`

- 성공: `200 OK` + 저장된 엔티티

### curl (PowerShell, 쿼리 파라미터)
```powershell
curl.exe -i -b cookies.txt -X POST "http://localhost:8080/api/debug?message=hello-db"
```

### 응답 예시
```json
{
  "id": 1,
  "message": "hello-db",
  "createdAt": "2026-06-24T01:55:00"
}
```

---

## 2. 전체 메시지 조회 — `GET /api/debug`

- 성공: `200 OK` + 배열

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt "http://localhost:8080/api/debug"
```
