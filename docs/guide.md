# 실행·배포·검증 가이드

Java 21과 Docker Compose v2를 사용한다. Python 작업은 uv로 실행·의존성을 관리한다.
이번 범위는 공통 기반과 새 모델이며, 구형 API는 제거했다. 실제 담당 기능은 이후 구현한다.

## 개발 환경

개발 환경을 `docker-compose.yaml` 하나로 통합했다.
기본 실행은 PostgreSQL 17·Redis·pgAdmin·RedisInsight를 띄운다.

```bash
docker compose up -d
./gradlew bootRun
```

백엔드도 컨테이너에서 실행하려면 app 프로필을 사용한다.

```bash
docker compose --profile app up -d --build
```

Compose 기본 DB명·계정·비밀번호와 Redis 비밀번호는 개발용 `bedrock`이다.
프로젝트 `.env`가 있으면 Compose와 bootRun이 해당 값을 사용한다.
bootRun은 기본 local 프로필로 실행하며 DB_URL, DB_USERNAME, DB_PASSWORD, REDIS_PASSWORD 등을 읽는다.
POSTGRES_DB를 우선 사용하고 기존 DB_HOST·DB_PORT·DB_NAME·DB_USER도 허용한다.
IDE에서는 local 프로필을 활성화한다. 별도 설정 파일은 BEDROCK_ENV_FILE로 지정할 수 있다.
로컬 `.env`는 `KEY=value` 형식으로 작성하며 값에 따옴표를 붙이거나 export 구문을 쓰지 않는다.
실제 OS 환경변수가 파일보다 우선한다. HTTP 개발 쿠키는 기본 Secure=false이며 COOKIE_SECURE로 변경할 수 있다.
배포 JAR는 local 프로필을 자동 활성화하지 않고 Compose가 주입한 환경변수를 사용한다.
bootRun에서는 DevTools 재시작을 비활성화해 기동 실패가 Gradle 실패로 전달되도록 한다.
Boot의 Compose 자동 실행은 기본 비활성화이며 DOCKER_COMPOSE_ENABLED=true로 켤 수 있다.
이 경우에도 같은 docker-compose.yaml의 인프라만 기본 실행한다.

- 상태: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- pgAdmin: `http://localhost:5050` (PGADMIN_EMAIL, PGADMIN_PASSWORD로 설정)
- 로그: bootRun은 `./logs`, 컨테이너는 `/app/logs`

개발 프로젝트 이름은 bedrock-local이고 새 PostgreSQL 볼륨은 postgres_data_v2다.
기존 PostgreSQL 15 볼륨은 재사용하거나 삭제하지 않는다.
기존 컨테이너가 같은 호스트 포트를 사용 중이면 중지한 뒤 실행한다.

## 신규 기능 연결

1. 신규 컨트롤러는 `@CurrentUser SessionPrincipal`로 사용자를 받는다.
2. 응답은 ApiResponse, 페이지 조회는 CursorPageResponse, 오류는 BusinessException과 담당 ErrorCode를 쓴다.
3. 다른 담당 기능은 common.port를 주입해 호출한다. 실제 구현이 없으면 503 FEATURE_NOT_READY다.
4. BE 1은 비밀번호·제공자 인증에 성공한 뒤 SessionAuthenticationService.signIn을 호출한다.
5. 클라이언트는 쓰기 전 GET /api/auth/csrf의 토큰과 동일한 쿠키를 전송한다. 로그인 후에는 토큰을 다시 받는다.
6. 웹 탭은 자신의 사용자 UUID를 X-Bedrock-Account-Id 헤더로 전송한다.

프런트엔드의 쿠키 전송 설정은 `credentials: 'include'`다.
세션 쿠키는 BEDROCK_SESSION이며 운영에서는 Secure=true를 유지한다.
실제 가입·로그인·동기화·암호화·반복 등의 기능 구현은 이번 기반 범위에 포함하지 않는다.

## DB 변경

Flyway만으로 스키마를 변경하고 JPA의 ddl-auto=validate를 유지한다.
V1~V3은 수정하지 않는다. V4는 구형 테이블이 비어 있는지 검사한 뒤 제거하고 V5가 새 스키마를 생성한다.
구형 DB에 데이터가 있으면 전환이 중단된다. 백업과 별도 이관 계획을 완료한 새 DB를 사용한다.
V4를 통과시키려고 기존 운영 데이터를 임의로 지우지 않는다.

- BE 1: V101, V102, ...
- BE 2: V201, V202, ...
- BE 3: V301, V302, ...

out-of-order=true이므로 V301 이후에 들어온 V101도 적용한다.
적용된 파일은 고치지 않고 새 SQL을 추가한다. 새 엔티티·컬럼 변경 시 같은 PR에 마이그레이션을 포함한다.
자세한 모델 계약은 [공통 구조](architecture.md)를 따른다.

## 이미지 빌드와 배포

Docker Hub에 로그인한 개발 머신에서 실행한다.

```bash
./scripts/build-push.sh 1.0.2
```

기본 이미지 이름은 eagleindesert/<프로젝트 디렉토리 이름>이며 이 저장소에서는
`eagleindesert/bedrock-app-backend`다. IMAGE_NAME으로 변경할 수 있다.
대상은 linux/amd64이며 buildx 컨테이너는 빌드 성공·실패 모두 restart=no로 남는다.
이 명령은 실제 이미지를 게시한다.

Ubuntu 24.04 x86_64 서버에는 다음 세 파일을 같은 디렉토리에 둔다.

```text
배포 디렉토리/
├── docker-compose-deployment.yaml
├── .env
└── start.sh
```

.env.example을 .env로 복사한 뒤 비밀번호, HTTPS 프런트엔드 Origin, SMTP, KMS 값을 설정한다.
DB·Redis는 외부 포트를 열지 않고 BE는 `127.0.0.1:${BE_HOST_PORT:-8080}`에만 바인딩한다.
호스트 Nginx에서 HTTPS를 종료하고 해당 포트로 전달한다.

```bash
chmod +x start.sh
./start.sh 1.0.2
# 직접 CLI에서도 같은 IMAGE_TAG를 사용한다.
# docker compose -f docker-compose-deployment.yaml up -d
```

버전 인자는 .env의 IMAGE_TAG를 갱신하고 pull 후 up을 수행한다.
인자가 없는 start.sh는 Compose up -d만 실행한다.
.env는 쉘 코드로 실행하지 않고 태그만 검증·갱신한다.
셸에 별도 IMAGE_TAG가 있으면 일반 Compose 규칙에 따라 셸 값이 우선한다.
배포 시 SWAGGER_ENABLED의 기본값은 false다.

로그는 named volume에 보존한다. access/application 로그 각각 20MB마다 gzip 파일로 나누며
최대 365일·10GB를 보관한다. 런타임 이미지는 비루트 JRE와 난독화 JAR로 구성된다.

## 검증

```bash
./gradlew test bootJarObfuscate
bash scripts/tests/deployment-scripts.sh
bash scripts/tests/obfuscated-smoke.sh
```

단위·MVC 검증은 공통 응답, 오류, CSRF, 계정 불일치, 세션 교체,
민감값 없는 로그, Argon2id, 포트 Stub 교체를 확인한다.
모델 검증은 DB 연결 없이 Hibernate 메타데이터를 구성하여 26개 엔티티의 컬럼·타입·NULL 조건을 정본 SQL과 대조한다.

DB 통합 검증은 Testcontainers의 독립 PostgreSQL 17에서 구형 데이터 삭제 거부,
빈 DB 전환, 26개 테이블, 멱등 마이그레이션과 역순 버전 적용을 확인한다.
전체 애플리케이션 검증은 Redis도 띄워 JPA validate, 모델 저장·Auditing·sync_seq,
세션의 실제 저장과 30분 만료를 확인한다. Docker가 없으면 두 통합 테스트 그룹(3개 테스트)을 명시적으로 중단 처리한다.

배포 스크립트 검증은 임시 디렉토리와 가짜 Docker로 실행 순서·태그 갱신·실패 시 buildx 정책을 확인한다.
실제 이미지 게시나 기존 .env 변경은 하지 않는다.
난독화 smoke 검증은 jq·curl·ripgrep·Docker·JDK가 필요하며 독립 DB·Redis·임시 이미지를 만든다.
이미지 기동, 사전 세션의 Redis 저장, 공통 인증 오류, Swagger, 서비스 이름의 실제 난독화를 확인하고 자원을 정리한다.
GitHub Actions의 Verify common foundation에서 같은 검증을 수행한다.
