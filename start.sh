#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"
COMPOSE_FILE="docker-compose-deployment.yaml"

if (( $# > 1 )); then
    printf 'Usage: %s [image-tag]\n' "$0" >&2
    exit 2
fi

if (( $# == 1 )); then
    TARGET_VERSION="$1"
    if [[ ! "${TARGET_VERSION}" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]]; then
        printf 'Invalid Docker image tag.\n' >&2
        exit 2
    fi
    if [[ ! -f .env ]]; then
        printf 'Create .env from .env.example before deployment.\n' >&2
        exit 1
    fi

    # .env를 실행하지 않고 태그 줄만 교체한다. 중복 키도 하나로 정리한다.
    ENV_TEMP="$(mktemp .env.tag.XXXXXX)"
    trap 'rm -f -- "${ENV_TEMP}"' EXIT
    awk -v tag="${TARGET_VERSION}" '
        /^[[:space:]]*(export[[:space:]]+)?IMAGE_TAG[[:space:]]*=/ {
            if (!written++) print "IMAGE_TAG=" tag
            next
        }
        { print }
        END { if (!written) print "IMAGE_TAG=" tag }
    ' .env > "${ENV_TEMP}"
    chmod --reference=.env "${ENV_TEMP}"
    mv -- "${ENV_TEMP}" .env
    export IMAGE_TAG="${TARGET_VERSION}"
    docker compose -f "${COMPOSE_FILE}" pull
fi

docker compose -f "${COMPOSE_FILE}" up -d
