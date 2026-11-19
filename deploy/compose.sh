#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[[ -f private/.env ]] || { echo 'Primero ejecutar bash deploy/preparar.sh'; exit 1; }
mkdir -p private/docker
# Entorno aislado: evita que un .env cargado en la terminal reemplace las claves del despliegue.
exec env -i PATH="$PATH" HOME="$HOME" DOCKER_CONFIG="$PWD/private/docker" DOCKER_HOST="${DOCKER_HOST:-unix:///var/run/docker.sock}" docker compose --env-file private/.env -f compose.yaml "$@"
