# Turnos y reservas

Servicio Java 21 y Spring Boot para construir disponibilidad consultando el servicio propio de catálogo y las ocupaciones de la cátedra. Puerto local 8082. Incluye registro de usuarios, login con JWT y consulta de cuenta propia. Todavía no crea holds ni persiste reservas.

## Requisitos

- Java 21 y acceso a Maven Central en la primera compilación; Maven se obtiene con `./mvnw`.
- Servicio catálogo ejecutándose, sincronizado y con `GET /api/catalogo/profesionales/{id}/agenda` disponible.
- Acceso a la red de cátedra y configuración técnica vigente para consultar ocupaciones.
- Docker con Compose para PostgreSQL y para ejecutar las pruebas con Testcontainers.

## Configuración y ejecución

Todas las direcciones y credenciales provienen del entorno. `.env` está excluido de Git. Ver `.env.example`.

| Variable | Uso |
| --- | --- |
| TURNOS_JWT_SECRET | Clave HS512 Base64, al menos 64 bytes; igual a CATALOGO_JWT_SECRET para propagar JWT de usuario |
| CATALOGO_URL | URL del servicio propio, por defecto http://localhost:8081 |
| CATEDRA_BASE_URL | URL de cátedra, sin credenciales en la URL |
| CATEDRA_TOKEN | JWT técnico; se envía únicamente a cátedra |
| TURNOS_PORT | Puerto HTTP, 8082 por defecto |
| TURNOS_ZONA | Zona de atención, America/Argentina/Mendoza por defecto |
| TURNOS_DB_URL | Base exclusiva de turnos, jdbc:postgresql://localhost:5434/turnos por defecto |
| TURNOS_DB_USER | Usuario de esa base, turnos por defecto |
| TURNOS_DB_PASSWORD | Clave local del usuario de PostgreSQL; obligatoria |

Preparar la clave de la base local y arrancar PostgreSQL:

```bash
python3 scripts/preparar-base-local.py
docker compose up -d --wait postgresql
```

El script genera una clave aleatoria en `.env` con permisos 0600 solo si falta; conserva una clave existente y el resto del archivo. No muestra secretos. Compose crea una instancia PostgreSQL independiente del catálogo y conserva sus datos en el volumen `turnos-postgresql`. El usuario y la base se llaman `turnos`; el puerto local 5434 evita el puerto 5433 del catálogo. Liquibase crea las tablas de turnos al arrancar Java. No se comparten bases, credenciales ni migraciones.

Si ya existe la configuración privada del catálogo en el repositorio hermano:

```bash
bash scripts/iniciar-local.sh
```

El script lee el `.env` local del catálogo para la clave JWT propia y las dos variables REST de cátedra, y el `.env` de turnos para su base de datos. No modifica archivos ni muestra sus valores. También admite la ruta de otro archivo local como argumento. Las credenciales de la base del catálogo no se reutilizan.

Con variables exportadas manualmente:

```bash
./mvnw spring-boot:run
```

La salud pública está en `GET /actuator/health`. Las APIs requieren JWT propio válido con ROLE_USER o ROLE_ADMIN. La cuenta técnica no autentica usuarios. `POST /api/register` y `POST /api/authenticate` son públicos. `GET /api/account` obtiene exclusivamente la cuenta del usuario autenticado. El JWT emitido por el login permite consultar disponibilidad y el catálogo; no hace falta un token de desarrollo. Ver el [contrato de usuarios](docs/USUARIOS_SEGURIDAD.md).

## Consulta de disponibilidad

```bash
curl -sS --max-time 20 \
  -H "Authorization: Bearer $TOKEN_DEMO" \
  'http://localhost:8082/api/turnos/disponibilidad?profesionalId=202&fecha=2026-11-09'
```

Usar una fecha entre hoy y los próximos 90 días. La consulta es informativa: no garantiza que un turno permanezca libre ni crea un bloqueo.

Los DTO, errores, validaciones y límites se describen en [contratos](docs/CONTRATOS_TURNOS.md).

## Pruebas y empaquetado

```bash
./mvnw test
./mvnw package
java -jar target/turnos-reservas-0.0.1-SNAPSHOT.jar
```

Las pruebas necesitan Docker. Usan PostgreSQL 18.6 mediante Testcontainers, reloj controlado y servidores HTTP locales con respuestas contractuales; no consumen cupos ni modifican datos de cátedra. Cubren registro, validación, contraseñas, duplicados concurrentes, login, cuenta propia, JWT, cálculo de disponibilidad, contratos y fallos de dependencias. También recorren registro → login → disponibilidad por HTTP. No se usa H2 ni la base local de desarrollo en las pruebas. Para ejecutar el JAR deben exportarse las variables de configuración anteriores.

Para detener PostgreSQL conservando los datos:

```bash
docker compose down
```
