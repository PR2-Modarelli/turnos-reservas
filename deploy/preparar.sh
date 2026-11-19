#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
set -a
source "${CATALOGO_ENV_FILE:-../catalogo-sincronizacion/.env}"
if [[ -f .env ]]; then source .env; fi
set +a
python3 deploy/preparar.py
