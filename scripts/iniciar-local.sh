#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Reutiliza configuracion privada existente sin copiarla ni imprimirla.
configuracion="${1:-../catalogo-sincronizacion/.env}"
if [[ ! -f "$configuracion" ]]; then
  echo "No existe el archivo de configuracion local indicado" >&2
  exit 1
fi
source "$configuracion"
# La base de turnos tiene credenciales propias en su .env local.
if [[ -f .env && "$configuracion" != ".env" ]]; then source .env; fi
export TURNOS_JWT_SECRET="${TURNOS_JWT_SECRET:-${CATALOGO_JWT_SECRET:-}}"
export CATEDRA_BASE_URL="${CATEDRA_BASE_URL:-${CATALOGO_CATEDRA_BASE_URL:-}}"
export CATEDRA_TOKEN="${CATEDRA_TOKEN:-${CATALOGO_CATEDRA_TOKEN:-}}"
: "${TURNOS_JWT_SECRET:?Falta la clave JWT propia de la aplicacion}"
: "${CATEDRA_BASE_URL:?Falta configurar la URL de catedra}"
: "${CATEDRA_TOKEN:?Falta configurar el token tecnico de catedra}"
export TURNOS_DB_URL="${TURNOS_DB_URL:-jdbc:postgresql://localhost:5434/turnos}"
export TURNOS_DB_USER="${TURNOS_DB_USER:-turnos}"
export TURNOS_DB_PASSWORD="${TURNOS_DB_PASSWORD:-}"
: "${TURNOS_DB_PASSWORD:?Falta TURNOS_DB_PASSWORD en el .env de turnos}"
export TURNOS_PORT="${TURNOS_PORT:-8082}"
export TURNOS_ZONA="${TURNOS_ZONA:-America/Argentina/Mendoza}"
export CATALOGO_URL="${CATALOGO_URL:-http://localhost:8081}"
exec ./mvnw spring-boot:run
