# Contratos de disponibilidad

## GET /api/turnos/disponibilidad

Requiere JWT propio de usuario con ROLE_USER o ROLE_ADMIN. Parámetros obligatorios `profesionalId` positivo y `fecha` ISO (`yyyy-MM-dd`), entre hoy y hoy+90 días inclusive en la zona de atención.

Respuesta 200:

```json
{
  "profesionalId": 15,
  "fecha": "2026-11-09",
  "zonaHoraria": "America/Argentina/Mendoza",
  "versionCatalogo": 54,
  "turnos": [{"horaInicio": "10:00:00", "horaFin": "10:30:00"}]
}
```

1. Propaga el JWT del usuario a `GET /api/catalogo/profesionales/{id}/agenda`. El catálogo valida firma y vigencia; ambos servicios utilizan la misma clave HS512 externalizada. No se comparte código Java, tablas o repositorios.
2. Recibe profesional, habilitación de categoría, reglas y versión en una lectura REPEATABLE_READ del catálogo. No almacena una réplica.
3. Genera intervalos completos de las reglas habilitadas del día. Profesional o categoría deshabilitada devuelve lista vacía. Un día sin agenda devuelve lista vacía sin llamar a cátedra.
4. Consulta `GET /api/appointment-occupancies?professionalId=...&from=...&to=...` con el JWT técnico, nunca con el JWT del usuario.
5. Excluye todas las posiciones HELD/CONFIRMED devueltas, turnos pasados o ya iniciados. No libera un HELD basándose en el reloj local: la fuente autoritativa de ocupaciones es cátedra. Ordena por inicio, elimina reglas duplicadas exactas y rechaza duraciones contradictorias para el mismo inicio.

Las horas se tratan como horas locales, sin desplazamiento UTC. Los intervalos que no completan la duración del turno no se ofrecen. La consulta no reserva ni reemplaza la validación vigente que deberá realizarse al crear el hold.

## Errores

| HTTP | code | Caso |
| --- | --- | --- |
| 400 | CONSULTA_INVALIDA | ID no positivo, fecha pasada o a más de 90 días |
| 400 | formato estándar Spring | Parámetro ausente o formato no convertible |
| 401 | sin garantía de code | JWT ausente, firma incorrecta o vencimiento |
| 403 | sin garantía de code | JWT válido sin rol de usuario/admin |
| 404 | PROFESIONAL_NO_ENCONTRADO | ID no existente en catálogo |
| 502 | RESPUESTA_EXTERNA_INVALIDA | Datos externos incompletos, relaciones incongruentes o valores inválidos |
| 503 | INTEGRACION_NO_CONFIGURADA | Falta dirección o token técnico |
| 503 | DEPENDENCIA_NO_DISPONIBLE | Conexión, timeout, error HTTP o JSON ilegible de una dependencia |

Los errores propios usan `application/problem+json`. No se devuelven cuerpos externos, tokens ni direcciones internas. Fallar al consultar ocupaciones no equivale a agenda libre. Conexión máxima 3 s y lectura 5 s por dependencia; no se reintentan estas consultas dentro de la misma solicitud. No se siguen redirecciones HTTP con credenciales.

## Propiedad y alcance

La disponibilidad no almacena una réplica de catálogo. Turnos ya persiste sus usuarios en `app_user`, con PostgreSQL y migraciones exclusivas; ver [usuarios y seguridad](USUARIOS_SEGURIDAD.md). No se permite usar la base del catálogo. La creación de holds y la consulta del proceso propio se describen en [bloqueos](BLOQUEOS.md). La [confirmación inicial REST](CONFIRMACION.md) también está implementada. El paso Kafka y los resultados finales, cancelación, reconciliación general de procesos, HTTPS y despliegue Compose completo siguen pendientes. El uso local de HTTP queda limitado al desarrollo; el despliegue debe proteger el transporte.
