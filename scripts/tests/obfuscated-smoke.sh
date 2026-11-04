#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$(mktemp -d)"
PROJECT_NAME="bedrock-smoke-$$"
IMAGE_NAME="bedrock-smoke:$$"

cleanup() {
    docker compose --project-directory "${FIXTURE}" -p "${PROJECT_NAME}" down -v >/dev/null 2>&1 || true
    docker image rm "${IMAGE_NAME}" >/dev/null 2>&1 || true
    rm -rf -- "${FIXTURE}"
}
trap cleanup EXIT

# CI에서만 사용할 독립 DB/Redis와 임시 쿠키 파일. 실제 프로젝트 .env는 읽지 않는다.
cat > "${FIXTURE}/compose.yaml" <<EOF
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: bedrock
      POSTGRES_USER: bedrock
      POSTGRES_PASSWORD: smoke-only
    healthcheck:
      test: [CMD-SHELL, 'pg_isready -U bedrock -d bedrock']
      interval: 2s
      timeout: 2s
      retries: 30
  redis:
    image: redis:7-alpine
    healthcheck:
      test: [CMD, redis-cli, ping]
      interval: 2s
      timeout: 2s
      retries: 30
  be:
    image: ${IMAGE_NAME}
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    ports:
      - '127.0.0.1::8080'
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/bedrock
      DB_USERNAME: bedrock
      DB_PASSWORD: smoke-only
      REDIS_HOST: redis
      COOKIE_SECURE: 'false'
EOF

docker build --platform linux/amd64 -t "${IMAGE_NAME}" "${PROJECT_ROOT}"
docker compose --project-directory "${FIXTURE}" -p "${PROJECT_NAME}" up -d
ADDRESS="$(docker compose --project-directory "${FIXTURE}" -p "${PROJECT_NAME}" port be 8080)"
BASE_URL="http://${ADDRESS}"
ready=0
for attempt in {1..60}; do
    if curl --silent --fail "${BASE_URL}/actuator/health" > /dev/null; then
        ready=1
        break
    fi
    sleep 2
done
if (( ready == 0 )); then
    docker compose --project-directory "${FIXTURE}" -p "${PROJECT_NAME}" logs be
    exit 1
fi

COOKIES="${FIXTURE}/cookies.txt"
csrf_token() {
    curl --silent --show-error --fail -c "${COOKIES}" -b "${COOKIES}" "${BASE_URL}/api/auth/csrf" |
        jq -er '.data.token'
}
TOKEN="$(csrf_token)"
[[ "$(csrf_token)" == "${TOKEN}" ]]
# 다른 요청에서도 같은 사전 세션이 복원되는지 확인한다.
docker compose --project-directory "${FIXTURE}" -p "${PROJECT_NAME}" exec -T redis \
    redis-cli --scan --pattern 'bedrock:session:v2:sessions:*' > "${FIXTURE}/redis-sessions.txt"
[[ -s "${FIXTURE}/redis-sessions.txt" ]]
STATUS="$(curl --silent --show-error -o "${FIXTURE}/unauthorized.json" -w '%{http_code}' \
    "${BASE_URL}/api/protected")"
[[ "${STATUS}" == 401 ]]
jq -e '.success == false and .error.code == "UNAUTHORIZED"' "${FIXTURE}/unauthorized.json" >/dev/null
curl --silent --show-error --fail "${BASE_URL}/v3/api-docs" | jq -e '.openapi' >/dev/null

docker run --rm --entrypoint sh "${IMAGE_NAME}" -c 'test ! -d /workspace && test ! -d /app/src'
jar tf "${PROJECT_ROOT}/build/libs/app-obfuscated.jar" > "${FIXTURE}/entries.txt"
if rg -q '\.java$|application-classes\.jar|app-.*-plain\.jar' "${FIXTURE}/entries.txt"; then
    printf 'Unobfuscated application/source artifact found in runtime JAR.\n' >&2
    exit 1
fi
awk '/com.bedrock.app.common.auth.SessionAuthenticationService -> / {
         target = $3
         sub(/:$/, "", target)
         if ($1 != target) renamed=1
     }
     END { exit !renamed }' "${PROJECT_ROOT}/build/obfuscation/mapping.txt"
printf 'Obfuscated image smoke checks passed.\n'
