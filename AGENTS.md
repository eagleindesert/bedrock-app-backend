# 프로젝트 작업 규칙

## Python 도구

- Python 의존성과 실행은 uv를 사용한다.
- 의존성 추가는 `uv add`, 환경 동기화는 `uv sync`, 코드·테스트 실행은 `uv run`을 사용한다.
- 독립 스크립트는 필요하면 `uv run --with 패키지명`으로 실행한다.
- pip 계열 명령을 사용하거나 권장하지 않는다. uv가 없으면 대체하지 말고 알린다.

## 가독성과 공통 기반

- 모든 파일을 사람이 읽기 쉽게 작성한다.
- 루트 패키지는 `com.bedrock.app`이다. 담당 경계와 계약은 `docs/architecture.md`를 따른다.
- BE 1: auth, mail, crypto, ai, conversation, ocr. 마이그레이션 V101부터.
- BE 2: item, link, sync, conflict, batch, undo. 마이그레이션 V201부터.
- BE 3: collection, setting, trash, recurrence, timetable, ics. 마이그레이션 V301부터.
- 담당 기능 작업은 자기 패키지와 담당 에러 Enum에서 진행하며, 다른 담당 기능은 `common.port`를 사용한다.
- 공통 기반을 변경하는 요청이 아니라면 common, 배포 파일, 기존 마이그레이션은 수정하지 않는다.
- 적용된 마이그레이션을 수정하지 않고 새 SQL을 추가한다. JPA `ddl-auto=validate`를 유지한다.
- 요청·응답 본문, 쿼리, 비밀번호, 토큰, 쿠키, SQL 바인딩 값은 로그에 남기지 않는다.
- 포트 Stub은 `FEATURE_NOT_READY`로 실패해야 한다. 암호화 대신 평문을 반환하거나 저장 성공을 가장하지 않는다.

## 검증

- Java 검증: `./gradlew test bootJarObfuscate`.
- 배포 스크립트 검증: `bash scripts/tests/deployment-scripts.sh`.
- Docker 환경의 실제 난독화 이미지 검증: `bash scripts/tests/obfuscated-smoke.sh`.
- 사용 가능한 검증만 수행한 경우 실행하지 못한 검증과 환경 제약을 명시한다.
