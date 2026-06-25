# Auth API 테스트 (`/api/auth`)

세션 기반 인증. 로그인 성공 시 `SESSION` 쿠키가 발급되며, 이후 인증이 필요한 요청은 이 쿠키를 함께 보내야 한다.
CSRF는 비활성화되어 있어 별도 토큰이 필요 없다.

---

## 1. 회원가입 — `POST /api/auth/signup`

- 인증: 불필요 (permitAll)
- 성공: `200 OK` (본문 없음)

### Request Body
```json
{
  "email": "test@example.com",
  "password": "password1234",
  "nickname": "테스터"
}
```

### curl (PowerShell)
```powershell
curl.exe -i -X POST "http://localhost:8080/api/auth/signup" `
  -H "Content-Type: application/json" `
  -d '{\"email\":\"test@example.com\",\"password\":\"password1234\",\"nickname\":\"테스터\"}'
```

> 이미 가입된 이메일이면 `IllegalArgumentException("이미 사용 중인 이메일입니다.")` 발생.

---

## 2. 로그인 — `POST /api/auth/login`

- 인증: 불필요 (permitAll)
- 성공: `200 OK` + `Set-Cookie: SESSION=...`
- 실패: `401` (`BadCredentialsException`)

### Request Body
```json
{
  "email": "test@example.com",
  "password": "password1234"
}
```

### curl (PowerShell, 쿠키를 cookies.txt에 저장)
```powershell
curl.exe -i -c cookies.txt -X POST "http://localhost:8080/api/auth/login" `
  -H "Content-Type: application/json" `
  -d '{\"email\":\"test@example.com\",\"password\":\"password1234\"}'
```

---

## 3. 로그아웃 — `POST /api/auth/logout`

- 인증: 필요 (세션 쿠키)
- Body: 없음
- 성공: `200 OK` (세션 무효화)

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X POST "http://localhost:8080/api/auth/logout"
```

---

## 4. 회원 탈퇴 — `DELETE /api/auth/withdraw`

- 인증: 필요 (세션 쿠키)
- Body: 없음
- 성공: `200 OK` (soft delete + 세션 무효화)

### curl (PowerShell)
```powershell
curl.exe -i -b cookies.txt -X DELETE "http://localhost:8080/api/auth/withdraw"
```
