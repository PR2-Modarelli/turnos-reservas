# Verificación del despliegue — 18 de noviembre de 2026

Comprobaciones realizadas en un entorno Compose separado del desarrollo manual:

- Empaquetado de turnos y construcción de las dos imágenes desde sus JAR.
- Validación del Compose mediante `config --quiet`.
- Preparación de certificados desde cero y conservación de secretos al repetirla.
- Arranque de los cuatro contenedores y healthchecks saludables.
- HTTPS con la CA local en ambos backends; conexión sin confiar en ella rechazada.
- Rechazo de un nombre de servidor incorrecto aunque la CA sea confiable.
- Rechazo HTTP 401 de las APIs protegidas sin JWT.
- Registro y login de una cuenta ficticia en la base nueva del despliegue.
- Consulta autenticada de catálogo inicializado y sin último error.
- Consulta de disponibilidad desde turnos, consumiendo catálogo por HTTPS y
  ocupaciones de la cátedra.
- Recreación de los cuatro contenedores y autenticación posterior de la misma
  cuenta con el mismo identificador; catálogo conserva su versión o avanza.
- Ejecución de Java con UID/GID 10001, sin root.
- Bases sin puertos publicados, con redes separadas; la base de turnos no puede
  resolver por DNS el servicio de base de catálogo.
- Certificados, configuración privada y JAR excluidos de Git.

## Prueba manual completada

Se probó desde la terminal, validando la CA local en cada solicitud:

- Registro por HTTPS: 201, cuenta activa y ROLE_USER.
- Login y disponibilidad: 200, catálogo versión 353.
- Bloqueo para profesional 202 el 19 de noviembre de 2026 a las 18:30: 201.
- Confirmación inicial REST: 202, ESPERANDO_TELEFONO.
- Envío del teléfono: 202, TELEFONO_ENVIADO.
- Resultado final por Kafka: CONFIRMADO, reserva 5863, sin último error.
- Cancelación: 200, CANCELLED, fecha original informada por la cátedra
  `2026-11-19T01:32:09.942763527Z` (UTC; la prueba fue el 18 de noviembre en Mendoza).

La verificación automática no crea reservas externas. La reserva ficticia de
esta prueba manual quedó cancelada. Los certificados locales son de desarrollo;
no constituyen por sí solos un despliegue público de producción.
