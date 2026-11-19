# Despliegue local de ambos backends con HTTPS

Este Compose pertenece a la issue #12 de turnos y coordina los dos repositorios
Java. Mantenerlos como carpetas hermanas:

```
proyecto-pr2/
  catalogo-sincronizacion/
  turnos-reservas/
```

## Requisitos

Java 21 (incluye `keytool`), Python 3, OpenSSL, Docker Engine, Docker Compose v2,
acceso a Maven Central y al registro de imágenes. ZeroTier debe permitir acceso
a REST, Redis y Kafka de la cátedra desde el host y los contenedores.

Los archivos privados `.env` de ambos repositorios deben estar configurados como
para el arranque habitual. No se deben publicar ni pegar en incidencias.

## Preparar, construir y arrancar

Desde `turnos-reservas`:

```bash
bash deploy/preparar.sh
bash deploy/construir.sh
bash deploy/compose.sh up -d --wait --wait-timeout 240
bash deploy/compose.sh ps
```

`preparar.sh` reutiliza la configuración de integración existente, genera
contraseñas nuevas y distintas para las bases de despliegue y crea una autoridad
certificadora local con certificados separados de catálogo y turnos. El JWT
propio de la aplicación se comparte entre ambos servicios, como en el entorno
actual. El usuario final y la cuenta técnica de la cátedra siguen separados.

La preparación conserva la configuración existente en `deploy/private/.env`.
Cambiar los `.env` originales después no modifica automáticamente el despliegue;
actualizar el archivo privado y recrear los contenedores cuando corresponda.
`CATALOGO_ENV_FILE` permite indicar otro archivo fuente al preparar por primera vez.

`construir.sh` empaqueta ambos proyectos con Maven y crea las imágenes. No ejecuta
las pruebas: antes de publicar, ejecutar `./mvnw verify` en catálogo y `./mvnw test`
en turnos. Solo los JAR se copian al contexto Docker: ni `.env` ni certificados ni
el código completo quedan dentro de las imágenes. Los contenedores Java se
inician con UID 10001, sin privilegios de root.

## Direcciones y certificados

| Servicio | Desde el host | Dentro de Compose |
| --- | --- | --- |
| Catálogo | https://localhost:8441 | https://catalogo:8081 |
| Turnos | https://localhost:8442 | https://turnos:8082 |

Las APIs no ofrecen HTTP en estos puertos. Se valida la cadena de confianza y el
nombre del servidor. Turnos utiliza un truststore con las CA públicas y la CA local al consultar
catálogo; no hay un cliente que acepte certificados arbitrarios.

Comprobar salud:

```bash
curl --fail --cacert deploy/private/ca.pem https://localhost:8441/management/health
curl --fail --cacert deploy/private/ca.pem https://localhost:8442/actuator/health
```

Las operaciones protegidas requieren el JWT del usuario, además de TLS. Registro
y login permanecen públicos; salud expone el estado permitido por cada servicio.
No usar `curl -k` ni deshabilitar la validación TLS para completar las pruebas.

Los puertos están enlazados a 127.0.0.1. El emulador Android accede al host mediante
10.0.2.2, incluido en los certificados. Al implementar KMP se debe configurar la
confianza en la CA local exclusivamente para desarrollo. Un dispositivo físico
requiere una dirección y un certificado adecuados a su red; esta configuración
no publica automáticamente las APIs en Internet.

## Separación y persistencia

Cada base tiene su usuario, contraseña, volumen y red interna exclusivos. No se
publican puertos de PostgreSQL. Cada backend solo comparte red con su propia
base y con la red de integración entre aplicaciones.

Los volúmenes `catalogo-data` y `turnos-data` pertenecen al proyecto Compose
`pr2-despliegue`. Se conservan al apagar o recrear contenedores:

```bash
bash deploy/compose.sh down
bash deploy/compose.sh up -d --wait --wait-timeout 240
```

Este entorno crea bases nuevas. No importa ni altera las bases del desarrollo
anterior en 5433/5434. Los usuarios de prueba anteriores no estarán en la base
nueva; se registran desde la API del despliegue. El catálogo se reconstruye desde
la cátedra cuando la sincronización está habilitada.

No usar `down -v` para apagar normalmente: elimina los volúmenes y sus datos.
Cambiar una contraseña en `.env` no cambia la contraseña de un usuario PostgreSQL
que ya existe en un volumen; requiere una rotación coordinada con la base.

## Integración y recuperación

Se reutilizan los endpoints y topics asignados por la cátedra. No se crean Redis
ni Kafka locales para sustituirlos. Los grupos de consumidores del despliegue se
separan de los del arranque manual, para no repartirse accidentalmente los avisos
entre bases locales distintas. La identidad técnica del proyecto no cambia.

Catálogo espera una base saludable y turnos espera la base propia y catálogo
saludables. La recuperación de procesos de turnos se activa explícitamente.
Los healthchecks comprueban HTTPS con la CA local; no prueban por sí solos que
la sincronización esté al día. Consultar `/api/catalogo/estado` autenticado para
comprobar versión, inicialización y último error.

Para ver errores sin compartir secretos:

```bash
bash deploy/compose.sh ps
bash deploy/compose.sh logs --tail 60 catalogo turnos
```

No publicar la salida de `docker compose config` sin `--quiet`, `docker inspect`,
variables de entorno o archivos privados: pueden contener credenciales.

## Alcance de seguridad

La CA y los certificados son de desarrollo. La CA dura un año y los certificados
de servidor 90 días. En un despliegue real deben sustituirse por certificados
confiables para el dominio y definirse renovación, exposición de red y gestión
de secretos. El almacén de confianza solo contiene certificados públicos; su
contraseña `changeit` no protege ninguna clave privada.

La configuración privada tiene permisos restrictivos y queda fuera de Git.
Cada aplicación recibe únicamente su propio PKCS12; no se monta la clave de la
CA. Compose entrega contraseñas mediante el entorno del contenedor, visible a
administradores de Docker: esto no equivale a un gestor de secretos.

Las conexiones a PostgreSQL se mantienen en redes internas aisladas. Los
protocolos externos de la cátedra son los de su contrato y no se convierten en
HTTPS/TLS desde esta configuración. No afirmar cifrado extremo a extremo para
esos enlaces externos.

Para renovar certificados, conservar una copia privada de la configuración y
contraseñas existentes; regenerar certificados y actualizar los montajes sin
borrar volúmenes ni cambiar por accidente las credenciales de las bases. Una
preparación incompleta requiere revisión manual; el script no la sobrescribe.

## Referencias

- [Orden de arranque y healthchecks en Compose](https://docs.docker.com/compose/how-tos/startup-order/)
- [Material de confianza SSL de Spring Boot](https://docs.spring.io/spring-boot/reference/features/ssl.html)

## Verificación reproducible

```bash
python3 deploy/verificar.py
bash deploy/compose.sh up -d --force-recreate --wait --wait-timeout 240
python3 deploy/verificar.py
```

El verificador crea una cuenta ficticia únicamente en la base nueva de turnos,
guarda sus credenciales en `deploy/private/prueba.json` y comprueba que puede
volver a autenticarse con el mismo identificador después de recrear contenedores.
También comprueba salud, rechazo de la CA no confiable, autenticación obligatoria,
catálogo inicializado y disponibilidad con integración entre servicios. No crea
bloqueos ni reservas externas. Debe estar disponible el profesional de prueba 202
y la integración real; si la cátedra está caída, no se considera aprobada esa
comprobación. El flujo con bloqueo, teléfono, confirmación y cancelación se
comprueba manualmente con un usuario del despliegue.
