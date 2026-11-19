#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f deploy/private/.env ]] || { echo 'Primero ejecutar bash deploy/preparar.sh'; exit 1; }
# Cada backend se empaqueta desde su repositorio; solo los JAR entran al contexto Docker.
(cd ../catalogo-sincronizacion && ./mvnw -DskipTests package)
./mvnw -DskipTests package
mkdir -p deploy/runtime
cp ../catalogo-sincronizacion/target/catalogo-0.0.1-SNAPSHOT.jar deploy/runtime/catalogo.jar
cp target/turnos-reservas-0.0.1-SNAPSHOT.jar deploy/runtime/turnos.jar
bash deploy/compose.sh build
