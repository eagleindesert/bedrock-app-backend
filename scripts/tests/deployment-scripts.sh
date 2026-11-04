#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$(mktemp -d)"
trap 'rm -rf -- "${FIXTURE}"' EXIT
mkdir -p "${FIXTURE}/bin" "${FIXTURE}/scripts"
cp "${PROJECT_ROOT}/start.sh" "${FIXTURE}/start.sh"
cp "${PROJECT_ROOT}/scripts/build-push.sh" "${FIXTURE}/scripts/build-push.sh"
cp "${PROJECT_ROOT}/docker-compose-deployment.yaml" "${FIXTURE}/docker-compose-deployment.yaml"
export DEPLOY_TEST_LOG="${FIXTURE}/docker.log"
export PATH="${FIXTURE}/bin:${PATH}"

cat > "${FIXTURE}/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${DEPLOY_TEST_LOG}"
if [[ "$*" == 'buildx inspect bedrock-x86-builder' ]]; then
    exit 1
fi
if [[ "$1 $2" == 'buildx build' && "${DEPLOY_TEST_FAIL_BUILD:-0}" == 1 ]]; then
    exit 7
fi
DOCKER
chmod +x "${FIXTURE}/bin/docker"
printf '%s\n' 'IMAGE_TAG=old' 'DB_PASSWORD=dummy-only' 'export IMAGE_TAG=duplicate' > "${FIXTURE}/.env"

"${FIXTURE}/start.sh"
[[ "$(cat "${DEPLOY_TEST_LOG}")" == 'compose -f docker-compose-deployment.yaml up -d' ]]
[[ "$(head -n 1 "${FIXTURE}/.env")" == 'IMAGE_TAG=old' ]]

: > "${DEPLOY_TEST_LOG}"
"${FIXTURE}/start.sh" 1.0.2
[[ "$(head -n 1 "${DEPLOY_TEST_LOG}")" == 'compose -f docker-compose-deployment.yaml pull' ]]
[[ "$(tail -n 1 "${DEPLOY_TEST_LOG}")" == 'compose -f docker-compose-deployment.yaml up -d' ]]
[[ "$(awk '/^IMAGE_TAG=/ { n++ } END { print n }' "${FIXTURE}/.env")" == 1 ]]
[[ "$(head -n 1 "${FIXTURE}/.env")" == 'IMAGE_TAG=1.0.2' ]]
[[ "$(tail -n 1 "${FIXTURE}/.env")" == 'DB_PASSWORD=dummy-only' ]]

: > "${DEPLOY_TEST_LOG}"
if "${FIXTURE}/start.sh" 'bad/tag' 2>/dev/null; then
    printf 'Invalid tag was accepted.\n' >&2
    exit 1
fi
[[ ! -s "${DEPLOY_TEST_LOG}" ]]
rm "${FIXTURE}/.env"
if "${FIXTURE}/start.sh" 2.0.0 2>/dev/null; then
    printf 'Missing deployment environment was accepted.\n' >&2
    exit 1
fi

: > "${DEPLOY_TEST_LOG}"
IMAGE_NAME=eagleindesert/test "${FIXTURE}/scripts/build-push.sh" 2.0.0
[[ "$(tail -n 1 "${DEPLOY_TEST_LOG}")" == 'update --restart=no buildx_buildkit_bedrock-x86-builder0' ]]
awk '/^buildx build/ && /--platform linux\/amd64/ && /eagleindesert\/test:2.0.0/ && /--push/ { found=1 }
     END { exit !found }' "${DEPLOY_TEST_LOG}"

: > "${DEPLOY_TEST_LOG}"
status=0
DEPLOY_TEST_FAIL_BUILD=1 "${FIXTURE}/scripts/build-push.sh" 2.0.1 || status=$?
[[ "${status}" == 7 ]]
[[ "$(tail -n 1 "${DEPLOY_TEST_LOG}")" == 'update --restart=no buildx_buildkit_bedrock-x86-builder0' ]]
printf 'Deployment script checks passed.\n'
