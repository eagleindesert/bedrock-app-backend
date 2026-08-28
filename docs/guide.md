# 프로젝트 실행 및 PR 가이드

새로운 팀원이나 로컬 환경 구축이 필요한 경우 아래 가이드를 따라 진행해 주세요.

---

## 1. 환경 변수 (.env) 세팅 가이드 🔒

본 프로젝트는 보안을 위해 데이터베이스 자격 증명 등의 민감 정보를 Git에 올리지 않습니다.
프로젝트를 처음 클론(Clone) 받았다면, **반드시 프로젝트 최상위 루트 디렉토리에 `.env` 파일을 직접 생성**해야 합니다.

### 📍 .env 위치 (ASCII 디렉토리 트리)

```text
bedrock-app-backend/ (프로젝트 루트 디렉토리)
├── .env  ◀ [이 위치에 직접 파일을 생성해야 합니다]
├── docker-compose.yml
├── Dockerfile
├── build.gradle
└── src/
```

### `.env` 파일 내용 예시

```env
DB_NAME=bedrock_db
DB_USER=postgres
DB_PASSWORD=password
PGADMIN_EMAIL=admin@admin.com
PGADMIN_PASSWORD=admin
```

> **주의**: 실제 배포 환경이나 팀 공용 DB를 사용할 경우 팀장에게 `.env` 설정값을 문의하여 작성하세요. 로컬 개발 시에는 위 기본값을 그대로 사용하셔도 무방합니다.

---

## 2. 프로젝트 로컬 실행 가이드 🚀

도커 데스크탑(Docker Desktop)이 실행 중인지 확인한 후 터미널에서 다음 명령어를 입력합니다.

### 2-1. 빌드 및 컨테이너 실행

```bash
# 도커 컴포즈를 이용해 전체 서비스를 백그라운드에서 빌드 후 실행합니다.
docker-compose up -d --build
```

> `--build` 옵션은 소스 코드가 변경되었을 때 최신 코드로 다시 빌드하도록 합니다.

### 2-2. 접속 확인

- **Spring Boot API**: `http://localhost:8080`
- **Swagger UI (API 문서)**: `http://localhost:8080/swagger-ui.html`
- **pgAdmin (DB 모니터링)**: `http://localhost:5050` (로그인 정보는 `.env`의 `PGADMIN_EMAIL`, `PGADMIN_PASSWORD` 사용)

### 2-4. Swagger UI 사용법

- 브라우저에서 `http://localhost:8080/swagger-ui.html` 접속 (OpenAPI 문서 원본은 `/v3/api-docs`)
- 인증이 필요한 API를 호출하려면 **`Auth > POST /api/auth/login`을 먼저 실행**합니다. 응답의 `SESSION` 쿠키가 브라우저에 저장되어 이후 요청에 자동으로 전송됩니다.
- 문서를 끄려면 `.env`(또는 컨테이너 환경변수)에 `SWAGGER_ENABLED=false`를 추가합니다. 운영 배포 시에는 이 값을 `false`로 두는 것을 권장합니다.

### 2-3. 서비스 종료

```bash
docker-compose down
```

> `down` 명령어를 사용해도 DB의 데이터는 삭제되지 않습니다. (볼륨으로 보존됨)

---

## 3. DB 스키마 변경 가이드 (Flyway) 🗄️

DB 스키마는 **Flyway 마이그레이션으로만** 변경합니다. Hibernate는 `spring.jpa.hibernate.ddl-auto=validate`로
설정되어 있어 엔티티와 실제 테이블이 다르면 애플리케이션이 아예 기동되지 않습니다.

### 3-1. 마이그레이션 파일 작성

엔티티(`@Entity`)를 추가하거나 컬럼을 바꿨다면, 같은 PR에 마이그레이션 파일을 함께 추가합니다.

```text
src/main/resources/db/migration/
├── V1__init.sql                       ◀ 베이스라인 스키마
├── V2__align_action_schema.sql
├── V3__normalize_constraint_names.sql
└── V4__{작업_내용}.sql                 ◀ 새 파일은 여기에
```

- 파일명 규칙: `V{다음_번호}__{스네이크_케이스_설명}.sql` (밑줄 **두 개**)
- 버전 번호는 팀에서 이미 사용된 가장 큰 번호 + 1을 씁니다.

### 3-2. 지켜야 할 규칙

- **이미 적용된 마이그레이션 파일은 절대 수정하지 않습니다.** Flyway가 체크섬을 검증하기 때문에
  파일을 고치면 다른 팀원의 애플리케이션이 기동에 실패합니다. 잘못된 내용은 새 버전 파일로 바로잡습니다.
- 애플리케이션은 기동 시 자동으로 마이그레이션을 적용합니다. 별도 명령은 필요 없습니다.
- 적용 이력은 `flyway_schema_history` 테이블에서 확인할 수 있습니다.

```bash
docker exec bedrock-postgres psql -U $DB_USER -d $DB_NAME \
  -c "select version, description, success from flyway_schema_history order by installed_rank"
```

> 기존에 `ddl-auto=update`로 만들어진 DB는 `baseline-on-migrate` 설정에 의해 V1이 자동으로
> 건너뛰어지므로, 데이터를 지우지 않아도 그대로 사용할 수 있습니다.

---

## 4. 깃허브 PR (Pull Request) 가이드 🤝

현재 저장소의 `main` 브랜치는 **Branch Protection Rule**이 적용되어 있어 직접 Push할 수 없습니다. 모든 코드 변경 사항은 아래 절차를 통해 PR을 거쳐야 합니다.

### 4-1. 브랜치 생성 및 작업

```bash
# 최신 메인 브랜치 가져오기
git checkout main
git pull origin main

# 새로운 작업 브랜치 생성 및 이동 (예: feature/debug-api)
git checkout -b feature/작업내용
```

### 4-2. 커밋 및 푸시

작업을 마친 후 변경 사항을 커밋하고 푸시합니다.

```bash
git add .
git commit -m "feat: 새로운 기능 추가"
git push -u origin feature/작업내용
```

### 4-3. Pull Request 생성 및 병합(Merge)

1. GitHub 저장소 페이지에 들어가면 `Compare & pull request` 버튼이 뜹니다.
2. PR 제목과 작업 내용을 상세히 적어 생성합니다.
3. 팀원의 **코드 리뷰 승인(Approve)**을 받습니다. (설정된 룰에 따라 승인이 필수일 수 있습니다.)
4. 리뷰가 완료되면 `Merge pull request` 버튼을 눌러 `main` 브랜치에 코드를 합칩니다.
5. 로컬 터미널로 돌아와 `git checkout main` -> `git pull origin main`으로 최신 상태를 동기화합니다.
