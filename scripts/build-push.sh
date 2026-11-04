#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIR_NAME="$(basename "${PROJECT_ROOT}")"
IMAGE_NAME="${IMAGE_NAME:-eagleindesert/${DIR_NAME}}"
IMAGE_TAG="${1:-latest}"
BUILDER_NAME="bedrock-x86-builder"
BUILDER_CONTAINER="buildx_buildkit_${BUILDER_NAME}0"

if (( $# > 1 )) || [[ ! "${IMAGE_TAG}" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]]; then
    printf 'Usage: %s [valid-image-tag]\n' "$0" >&2
    exit 2
fi

disable_builder_restart() {
    if docker container inspect "${BUILDER_CONTAINER}" >/dev/null 2>&1; then
        docker update --restart=no "${BUILDER_CONTAINER}" >/dev/null
    fi
}

trap disable_builder_restart EXIT
if ! docker buildx inspect "${BUILDER_NAME}" >/dev/null 2>&1; then
    docker buildx create --name "${BUILDER_NAME}" --driver docker-container --use
else
    docker buildx use "${BUILDER_NAME}"
fi

# 빌드 실패 때도 EXIT trap으로 새 빌더의 자동 재시작을 해제한다.
docker buildx inspect "${BUILDER_NAME}" --bootstrap >/dev/null
disable_builder_restart
docker buildx build \
    --builder "${BUILDER_NAME}" \
    --platform linux/amd64 \
    -t "${IMAGE_NAME}:${IMAGE_TAG}" \
    --push \
    "${PROJECT_ROOT}"
