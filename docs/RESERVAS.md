# Consulta y cancelación de reservas propias

## Identidad y fuente de datos

La API requiere JWT de una cuenta propia activa. El servidor obtiene `externalPatientId` del `publicId` de esa cuenta y utiliza el JWT técnico únicamente para integrar con la cátedra. El cliente no puede elegir la identidad del paciente.

Los listados se consultan en la cátedra en cada solicitud. Antes de devolverlos se valida el paciente de todas las filas, sus datos esenciales, filtros y paginación. Una respuesta con reservas ajenas falla cerrada con 502; no se devuelve parcialmente ni se expone su contenido en el error. No se propagan enlaces externos ni errores con datos de pacientes.

El detalle y la cancelación requieren además la asociación local entre proceso y usuario; un proceso ajeno o desconocido devuelve 404 antes de llamar a la cátedra. Al observar un proceso conocido se comprueban también profesional, fecha, hora e identificador de reserva. La reconstrucción general tras pérdida de la base local sigue siendo una tarea de recuperación independiente.

## Listado

`GET /api/turnos/reservas`

| Parámetro | Condición |
| --- | --- |
| `estado` | Opcional: PHONE_PENDING, CONFIRMED, CANCELLED o FAILED. |
| `desde`, `hasta` | Fechas ISO inclusivas; desde no puede ser posterior a hasta. |
| `pagina` | Base cero, predeterminado 0, máximo 100000. |
| `tamanio` | Entre 1 y 100, predeterminado 20. |

Respuesta 200: objeto con `reservas`, `total`, `pagina` y `tamanio`. Cada reserva conserva los nombres del contrato externo: `reservationId`, `reservationProcessId`, `appointmentDate`, `startTime`, `endTime`, `professionalId`, nombres del profesional y paciente, `patientPhone`, `status` y fechas de auditoría/cancelación. Los datos pertenecen exclusivamente al paciente autenticado.

Los filtros externos se construyen en el servidor. No se admite una identidad alternativa mediante parámetros del cliente. El orden es el predeterminado de la cátedra: fecha y hora descendentes. El total se obtiene de `X-Total-Count` y debe ser válido.

## Detalle

`GET /api/turnos/reservas/{reservationProcessId}`

El identificador es el proceso remoto devuelto en el listado, por ejemplo `process-...`; no es el UUID local de `/api/turnos/procesos/{id}`.

El contrato no define un GET individual ni un filtro por proceso. Se recorre el listado del paciente, en páginas de 100, hasta encontrarlo o agotar el total. Hay un máximo de 100 páginas; superar ese límite devuelve 503 `LIMITE_CONSULTA_RESERVAS`, sin afirmar que la reserva no existe. Una reserva ausente devuelve 404 `RESERVA_NO_ENCONTRADA`.

Las observaciones válidas actualizan los procesos locales conocidos: CONFIRMED a CONFIRMADO, FAILED a PROCESO_INVALIDO y CANCELLED a CANCELADO. Una lectura atrasada no resucita una cancelación local ni pisa un intento de cancelación en curso o de resultado desconocido.

## Cancelación

`POST /api/turnos/reservas/{reservationProcessId}/cancelacion`

Body opcional:

```json
{"motivo":"Cambio solicitado por el usuario"}
```

El motivo admite hasta 500 caracteres. Campos adicionales, incluido cualquier identificador de paciente, se rechazan. No se necesita `Idempotency-Key`: el contrato garantiza que la cancelación remota de una reserva ya CANCELLED es idempotente.

1. Se verifica la cuenta y la asociación local con el propietario.
2. Se consulta el estado vigente y la identidad de la reserva en la cátedra.
3. Si está CANCELLED, se conserva ese resultado y se devuelve 200 sin otro POST.
4. Solo CONFIRMED admite un nuevo envío. Otros estados devuelven 409 `RESERVATION_NOT_CANCELLABLE`.
5. Se guarda CANCELANDO y el motivo en una transacción corta, antes del envío HTTP. La llamada externa ocurre fuera de esa transacción.
6. Una respuesta válida debe identificar el mismo proceso/reserva, tener estado CANCELLED y fecha de cancelación. Se guarda CANCELADO y se cancelan envíos de teléfono pendientes.

Respuesta 200: `reservationId`, `reservationProcessId`, `status: CANCELLED` y `cancelledAt`. Un intento concurrente en curso devuelve 202 con `reservationProcessId` y `estado`; no equivale a una cancelación exitosa.

Una preparación reciente impide otro envío durante 30 segundos. Tras ese plazo o ante CANCELACION_DESCONOCIDA se vuelve a consultar la cátedra; solo si sigue CONFIRMED se permite repetir la operación remota idempotente. No hay reenvíos automáticos de cancelación en segundo plano.

## Errores y recuperación

- Los rechazos conocidos 400/404/409 se conservan con su código y no se consideran cancelación exitosa.
- Un fallo de credenciales técnicas se informa como 503 `INTEGRACION_NO_AUTORIZADA`, sin cerrar la sesión del usuario final.
- Timeouts, cortes de conexión, respuestas inválidas y 5xx dejan CANCELACION_DESCONOCIDA cuando el intento ya se envió. No se afirma que la cátedra no haya cancelado.
- Si se pierde el guardado después de un 200 remoto, permanece la preparación durable. Una consulta posterior observa CANCELLED y reconcilia el proceso sin volver a cancelar.
- El evento `AppointmentCancelled` valida grupo, proceso, hold, solicitud, reserva, profesional, fecha y hora; guarda CANCELADO y el motivo opcional. Se aplica incluso si llega antes de guardar la respuesta HTTP. Eventos posteriores de confirmación no hacen retroceder CANCELADO.

La prueba manual de cancelación requiere elegir una reserva propia de demostración. Las pruebas automatizadas trabajan con servidores HTTP, PostgreSQL y Kafka aislados de la cátedra.
