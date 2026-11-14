# 공통 HTTP 계약

현재 범위는 공통 기반과 새 모델이다. 구형 `/api/v1/items`, `/api/v1/collections`, 액션·디버그 API와
이전 가입·로그인 구현은 제거했다. 실제 업무 API는 각 담당 패키지에서 추가한다.

공통 기반에서 제공하는 경로는 다음과 같다.

| 메서드·경로 | 용도 |
| --- | --- |
| GET / | 서버 안내 |
| GET /actuator/health | DB·Redis를 포함한 상태 확인 |
| GET /api/auth/csrf | 사전 세션과 CSRF 토큰 발급 |
| GET /v3/api-docs | OpenAPI JSON, Swagger 활성화 시 |
| GET /swagger-ui.html | API 문서, Swagger 활성화 시 |

CSRF 응답은 `{"success":true,"data":{"headerName":"X-CSRF-TOKEN","token":"..."},"error":null}`이다.
쓰기 요청은 동일한 `BEDROCK_SESSION` 쿠키와 CSRF 헤더를 전송한다.
BE 1은 자격 증명 검증 후 `SessionAuthenticationService.signIn`을 호출한다.
이 메서드가 세션 ID와 CSRF 토큰을 교체하므로 클라이언트는 로그인 직후 토큰을 다시 받아야 한다.

신규 컨트롤러는 `@CurrentUser SessionPrincipal`로 사용자 정보를 받으며
응답에는 `ApiResponse<T>`와 필요하면 `CursorPageResponse<T>`를 사용한다.
에러는 `BusinessException(ErrorCode)`로 전달한다. 없는 대상과 타인 소유 대상은 동일한
404 `RESOURCE_NOT_FOUND`를 사용한다. 204 응답에는 본문을 넣지 않는다.
웹 탭의 `X-Bedrock-Account-Id`가 세션 계정과 다르면 403 `ACCOUNT_MISMATCH`다.

인증과 실행 환경의 자세한 규칙은 [실행 가이드](../guide.md)와 [공통 구조](../architecture.md)를 따른다.
