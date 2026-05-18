# Bedrock App Backend

Docker 기반 Spring Boot & PostgreSQL 백엔드 애플리케이션입니다.

## 🚀 Quick Start

1. 프로젝트 루트에 `.env` 파일 생성
```env
DB_NAME=bedrock_db
DB_USER=postgres
DB_PASSWORD=password
PGADMIN_EMAIL=admin@admin.com
PGADMIN_PASSWORD=admin
```

2. 서비스 빌드 및 실행
```bash
docker-compose up -d --build
```

---

## 📂 Documentation

* [시스템 설계서 (docs/architecture.md)](docs/architecture.md)
* [실행 및 PR 가이드 (docs/guide.md)](docs/guide.md)
