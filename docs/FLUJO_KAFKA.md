# Teléfono y resultado final por Kafka

La confirmación inicial REST deja el proceso esperando información adicional. El servicio de turnos consume los eventos de la cátedra y persiste solicitudes, correlación y resultados en su propia base PostgreSQL. No crea ni comparte tablas del catálogo.

## Configuración

Las variables de `.env.example` se completan con la información de `integration`:

- `TURNOS_KAFKA_ENABLED`: activar el consumidor y el publicador de pendientes.
- `TURNOS_KAFKA_BOOTSTRAP_SERVERS`: broker accesible desde el servicio.
- `TURNOS_KAFKA_GROUP_ID`: identidad técnica del proyecto, no el usuario final.
- `TURNOS_KAFKA_CONSUMER_GROUP`: identificador persistente y exclusivo de turnos.
- `TURNOS_KAFKA_ACTIONS_TOPIC`: `alumnos.turnos.acciones.{groupId}`.
- `TURNOS_KAFKA_PHONE_TOPIC`: `catedra.turnos.telefono.{groupId}`.

`bash scripts/iniciar-local.sh` activa Kafka por defecto, reutiliza el broker del catálogo y, si no hay una configuración específica, obtiene el groupId del sufijo del topic de catálogo configurado. Los otros dos nombres se construyen con las convenciones del contrato. El consumer group predeterminado es `turnos-{groupId}`. Los valores explícitos de turnos prevalecen. Se puede desactivar con `TURNOS_KAFKA_ENABLED=false` para una ejecución sin integración. No se imprimen secretos ni se registran teléfonos en logs.

No cambiar el consumer group en cada arranque: se perdería la continuidad de los offsets. Se usa `earliest` para un grupo nuevo, sin creación automática de topics.

## Eventos recibidos

`eventId` identifica una entrega funcional y la message key debe coincidir con `reservationProcessId`. Se exige `schemaVersion: 1`, hold, fecha de ocurrencia y correlación válida; se ignoran campos adicionales compatibles.

| Evento | Efecto |
| --- | --- |
| `AdditionalInformationRequested` | Conserva `eventId` como `requestEventId`, vencimiento y campo `PHONE_NUMBER`; habilita el formulario de teléfono. |
| `AdditionalInformationRejected` | Conserva el motivo y habilita una corrección antes del vencimiento, con una nueva clave y un nuevo evento de salida. |
| `AppointmentConfirmed` | Valida proceso, hold, solicitud, profesional, fecha y hora; guarda `reservationId` y estado `CONFIRMADO`. |
| `AppointmentProcessExpired` | Guarda `VENCIDO` y cancela envíos pendientes. |
| `AppointmentProcessInvalid` | Guarda `PROCESO_INVALIDO` y cancela envíos pendientes. |

Un resultado terminal no retrocede por eventos tardíos. No se considera una publicación de teléfono como una reserva confirmada. El vencimiento local impide publicar fuera de plazo, pero el resultado final se obtiene del evento de la cátedra.

La recepción guarda primero el evento en `evento_turnos_entrada` y aplica la transición en la misma transacción. El offset se confirma después del commit. Los eventos que llegan antes de guardar el hold o antes de recibir la solicitud quedan pendientes y se reprocesan periódicamente. Una solicitud ya correlacionada no se reemplaza por otra distinta.

Un mensaje ilegible o con sobre incompatible se conserva en `evento_turnos_descartado` por topic, partición y offset, sin bloquear los siguientes mensajes. Los eventos legibles con datos incompatibles se marcan procesados con un código de diagnóstico. La cuarentena requiere revisión; no se convierte un mensaje incompatible en una reserva válida ni se descarta sin registro. Los fallos de base de datos conservan el offset y se reintentan.

## API propia

Ambos endpoints exigen JWT de una cuenta activa. Otro propietario recibe 404. No se acepta identidad de paciente enviada por el cliente.

### GET `/api/turnos/procesos/{id}/informacion`

Devuelve `id`, `estado`, `requestEventId`, `reservationId`, `ultimoError`, `expiresAt` del paso de confirmación y `requiereTelefono`. No devuelve el teléfono almacenado. La consulta general del proceso sigue disponible en `/api/turnos/procesos/{id}`.

### POST `/api/turnos/procesos/{id}/telefono`

Cabeceras: `Authorization: Bearer ...`, `Content-Type: application/json` e `Idempotency-Key` UUID.

```json
{"telefono":"+54 (9261) 555-1234"}
```

Se eliminan espacios, guiones, paréntesis y puntos. El teléfono normalizado debe cumplir `^\+?[0-9]{7,15}$`.

Responde 202 cuando el intento queda guardado para publicación y el proceso pasa a `TELEFONO_ENVIADO`. Este estado incluye el envío encolado; no prueba que Kafka ni la cátedra ya lo hayan aceptado. La respuesta tiene la misma forma que la consulta de información.

| Condición | HTTP / código |
| --- | --- |
| Teléfono inválido | 400 `INVALID_PHONE_NUMBER` o `VALIDATION_ERROR` |
| Otra identidad o proceso inexistente | 404 `PROCESO_NO_ENCONTRADO` |
| Falta solicitud o ya existe un intento en curso | 409 `TELEFONO_NO_SOLICITADO` |
| Plazo vencido | 409 `PROCESO_VENCIDO` |
| Misma clave con otro teléfono | 409 `IDEMPOTENCY_KEY_REUTILIZADA` |
| Kafka desactivado o sin grupo | 503 `KAFKA_NO_CONFIGURADO` |

La misma clave y el mismo teléfono normalizado devuelven el proceso sin generar otro evento. Una corrección tras rechazo usa otra clave. Si se pierde la respuesta HTTP, se repite con la clave original.

## Publicación y recuperación

La transición del proceso y la inserción en `telefono_salida` son atómicas. El publicador recupera pendientes de PostgreSQL después de un reinicio, publica con key `reservationProcessId` y conserva un `eventId` estable. Solo marca publicado tras recibir el ACK del broker. Los resultados terminales o vencimientos cancelan pendientes aún no publicados.

La entrega es al menos una vez: si el ACK se pierde o no se guarda localmente, puede repetirse el mismo evento; la cátedra debe deduplicarlo por `eventId`, conforme al contrato. No se promete exactamente una entrega. Los mensajes incompatibles en cuarentena y los procesos sin eventos correlacionables requieren diagnóstico o la reconciliación general prevista en una tarea posterior.

## Verificación

Las pruebas usan PostgreSQL y Kafka aislados de la cátedra. Cubren propietario, validación y normalización, claves repetidas, concurrencia, vencimiento, duplicados, eventos adelantados, estados terminales, corrección tras rechazo, broker no disponible y ACK sin guardado local. Una prueba con broker real recorre solicitud, publicación correlacionada y resultado final; otra comprueba la cuarentena sin bloqueo de partición.

La comprobación manual con el servidor de la cátedra se hace con un bloqueo nuevo y un teléfono de prueba acordado. Los holds y procesos de demostraciones anteriores pueden estar vencidos.
