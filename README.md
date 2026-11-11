# Bedrock App Backend

Java 21 · Spring Boot 4 · PostgreSQL 17 · Flyway · Redis Session 기반 백엔드입니다.
`공통-파일.md`의 공통 기반과 새 모델을 적용했습니다.

기존 액션·임의 JSON 아이템·공유 컬렉션 모델과 API는 제거했습니다.
새 명세의 31개 테이블과 JPA 모델, 공통 보안·응답·로그·포트·배포 기반을 제공합니다.
가입·로그인·동기화·암호화·반복 등 실제 담당 기능은 이후 각 패키지에서 구현합니다.
미구현 포트는 `503 FEATURE_NOT_READY`로 실패합니다.

```bash
# 개발 인프라: PostgreSQL, Redis, pgAdmin, RedisInsight
docker compose up -d

# 백엔드까지 컨테이너로 실행
docker compose --profile app up -d --build

# 또는 IDE / 로컬 Java 실행
./gradlew bootRun
```

개발 Compose는 `docker-compose.yaml` 하나로 통합했습니다.
구형 PostgreSQL 볼륨과 섞이지 않도록 별도 프로젝트와 `postgres_data_v2` 볼륨을 사용합니다.
`bootRun`은 기본 local 프로필에서 프로젝트 `.env`의 접속 정보를 읽습니다.

- 상태: `http://localhost:8080/actuator/health`
- Swagger: `http://localhost:8080/swagger-ui.html`
- 공통 CSRF: `GET /api/auth/csrf`
- 세션: `BEDROCK_SESSION`, UUID 사용자, 무활동 30분

배포는 `docker-compose-deployment.yaml`, `.env`, `start.sh` 세 파일로 수행합니다.
`.env.example`을 복사하고 값을 설정한 뒤 `./start.sh <버전>`을 실행하세요.
기존 V1~V3은 보존하며 V4~V5로 전환합니다. 구형 DB에 데이터가 있으면 자동 삭제하지 않고 중단합니다.

[실행·배포·검증 가이드](docs/guide.md) · [공통 구조와 담당 경계](docs/architecture.md)
