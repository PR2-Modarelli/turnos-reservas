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
# Los topics y el grupo se obtienen de integration, nunca del JWT del usuario final.
export TURNOS_KAFKA_ENABLED="${TURNOS_KAFKA_ENABLED:-true}"
export TURNOS_KAFKA_BOOTSTRAP_SERVERS="${TURNOS_KAFKA_BOOTSTRAP_SERVERS:-${CATALOGO_KAFKA_BOOTSTRAP_SERVERS:-}}"
grupo_turnos="${TURNOS_KAFKA_GROUP_ID:-}"
if [[ -z "$grupo_turnos" && "${CATALOGO_KAFKA_CATALOG_TOPIC:-}" == catedra.catalog.* ]]; then
  grupo_turnos="${CATALOGO_KAFKA_CATALOG_TOPIC#catedra.catalog.}"
fi
export TURNOS_KAFKA_GROUP_ID="$grupo_turnos"
export TURNOS_KAFKA_CONSUMER_GROUP="${TURNOS_KAFKA_CONSUMER_GROUP:-turnos-${grupo_turnos}}"
export TURNOS_KAFKA_ACTIONS_TOPIC="${TURNOS_KAFKA_ACTIONS_TOPIC:-alumnos.turnos.acciones.${grupo_turnos}}"
export TURNOS_KAFKA_PHONE_TOPIC="${TURNOS_KAFKA_PHONE_TOPIC:-catedra.turnos.telefono.${grupo_turnos}}"
exec ./mvnw spring-boot:run
