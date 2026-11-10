# Confirmación inicial REST

## Alcance

Permite que el propietario de un hold vigente inicie la confirmación con cátedra. Una respuesta REST aceptada significa `WAITING_FOR_PHONE`, representado localmente como `ESPERANDO_TELEFONO`. **No es una reserva confirmada definitivamente.** El [intercambio de teléfono y los resultados finales por Kafka](FLUJO_KAFKA.md) continúa este flujo.

## POST /api/turnos/procesos/{id}/confirmacion

JWT propio de usuario y cuenta persistida/activa obligatorios. `id` es el UUID local del proceso, no un holdId libre elegido por el cliente. Sin body; se admite también el body vacío `{}`. Otros datos enviados, incluidos identificadores o nombres de paciente, se rechazan con 400.

El propietario se obtiene del JWT y se verifica con el usuario guardado en el proceso. Un ID inexistente o perteneciente a otra cuenta devuelve 404. Los datos enviados a cátedra son `externalPatientId` = UUID público estable de la cuenta, `patientFirstName` y `patientLastName` de esa cuenta. Los nombres se recortan en sus extremos y deben tener entre 2 y 100 caracteres. Una cuenta sin esos datos válidos recibe 400 `DATOS_PACIENTE_INCOMPLETOS`; no se inventan nombres ni se toman del body. Este módulo no añade edición de perfiles.

Antes del primer envío se valida la agenda vigente mediante catálogo y el estado/vencimiento del bloqueo. Si falla esa preparación, el hold sigue BLOQUEADO y no se envía la confirmación; puede repetirse después de corregir el problema mientras siga vigente.

Respuesta 202 y `Location: /api/turnos/procesos/<id>`:

```json
{
  "id":"<uuid-local>",
  "profesionalId":202,
  "fecha":"2026-11-10",
  "horaInicio":"11:30:00",
  "estado":"ESPERANDO_TELEFONO",
  "holdId":"<hold-remoto>",
  "reservationProcessId":"<proceso-remoto>",
  "expiresAt":"2026-11-08T16:05:00Z",
  "ultimoError":null,
  "confirmacionExpiresAt":"2026-11-08T16:05:00Z"
}
```

Ejemplo ilustrativo. `expiresAt` conserva el vencimiento del hold original. `confirmacionExpiresAt` guarda, por separado y sin inventarlo, el vencimiento devuelto al aceptar la confirmación. La consulta general `GET /api/turnos/procesos/{id}` expone también este campo nuevo, nulo antes de aceptar la confirmación.

## Envío remoto y validación

Se envía `POST /api/appointment-holds/{holdId}/confirm` con JWT técnico. El identificador del hold se codifica como segmento de ruta. No se propaga el JWT técnico al catálogo ni al cliente.

Solo se acepta como respuesta válida un 202 con `status: WAITING_FOR_PHONE`, `expiresAt` presente y `reservationProcessId` exactamente igual al conservado al crear el hold. Una respuesta de otro proceso, incompleta o con un estado inesperado se trata como incierta, no como confirmación exitosa.

## Persistencia y concurrencia

La migración `202611080001` amplía el estado del proceso y agrega `confirmacion_iniciada_en` y `confirmacion_vence_en`. Conserva los registros y migraciones anteriores.

1. Consulta y valida propietario, cuenta, agenda y configuración.
2. Bloquea brevemente la fila del proceso y vuelve a verificar estado/vencimiento.
3. Si corresponde, persiste CONFIRMANDO antes de enviar el POST; la transacción ya terminó al llamar a cátedra.
4. Guarda ESPERANDO_TELEFONO al recibir una respuesta válida, o el rechazo/resultado incierto correspondiente.

Dos solicitudes simultáneas no envían dos confirmaciones. El ID del proceso identifica una única confirmación inicial, por lo que este endpoint no requiere otra Idempotency-Key.

| Estado actual | Resultado al repetir el endpoint |
| --- | --- |
| BLOQUEADO y vigente | Puede iniciar el envío después de validar |
| VENCIDO | 409 HOLD_EXPIRED, sin envío |
| CONFIRMANDO | 202 con estado actual, sin envío |
| ESPERANDO_TELEFONO | 202 con estado guardado, sin envío ni consulta nueva a catálogo |
| CONFIRMACION_DESCONOCIDA | 202 con incertidumbre guardada, sin reenvío |
| CONFIRMACION_RECHAZADA | Repite el HTTP/código guardados, sin reenvío |
| Otros estados sin hold conocido | 409 ESTADO_NO_CONFIRMABLE |

Los nuevos estados siguen bloqueando otra intención del mismo usuario/slot. Solo un rechazo definitivo cuyo hold conocido ya haya vencido puede pasar a VENCIDO y liberar esa intención. Un resultado desconocido o aceptado no se considera un hold vencido automáticamente: podría haber avanzado en cátedra.

## Pérdida de respuesta y recuperación

Conexión 3 segundos, lectura 5 segundos, sin reintento automático del POST. Un timeout, desconexión, 5xx o respuesta inconsistente conserva CONFIRMACION_DESCONOCIDA. Un 409 `RESERVATION_PROCESS_ALREADY_CONFIRMED` conserva ese mismo estado con código `CONFIRMACION_PREVIA_NO_VERIFICADA`: no permite deducir por sí solo el estado vigente de la reserva.

CONFIRMANDO sin avance durante 30 segundos pasa a CONFIRMACION_DESCONOCIDA al consultar/iniciar operaciones. Es un umbral local para detectar un intento interrumpido, no el TTL de la reserva. Una respuesta tardía del intento original puede resolver la incertidumbre. No se vuelve a enviar por repetir el endpoint ni por reiniciar la aplicación.

Si falla PostgreSQL después del 202 remoto, el intento CONFIRMANDO ya está persistido. Se devuelve 503 con `procesoId`, sin deshacer la operación externa ni reenviarla. Los eventos Kafka correlacionables permiten avanzar el proceso incluso si se pierde la respuesta REST. La reconciliación general con el listado de reservas sigue pendiente; no se afirma que este flujo recupere todos los resultados remotos perdidos.

ESPERANDO_TELEFONO es el último estado observado por REST. Aunque se alcance su vencimiento, este módulo no inventa un resultado final; se necesitará observar cátedra/Kafka para concluir confirmación, rechazo o expiración.

## Errores relevantes

| HTTP | Código | Situación |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | Body no admitido o ID mal formado |
| 400 | DATOS_PACIENTE_INCOMPLETOS | Cuenta sin nombre/apellido aptos para el contrato |
| 400 | INVALID_SLOT | Agenda vigente no permite ese slot |
| 401 | INVALID_CREDENTIALS o seguridad JWT | Cuenta no válida o autenticación incorrecta |
| 404 | PROCESO_NO_ENCONTRADO | No existe proceso propio |
| 409 | HOLD_EXPIRED | Bloqueo vencido localmente |
| 409 | ESTADO_NO_CONFIRMABLE | No hay hold conocido que confirmar |
| 503 | RESULTADO_CONFIRMACION_DESCONOCIDO | No se puede asegurar el resultado del POST |
| 503 | CONFIRMACION_PREVIA_NO_VERIFICADA | Cátedra informa confirmación previa que no quedó verificada localmente |
| 503 | INTEGRACION_NO_AUTORIZADA | Rechazo de credenciales técnicas; no cierra sesión del usuario |

Los rechazos contractuales definitivos conservan su HTTP/código: 400 VALIDATION_ERROR/INVALID_SLOT/EXTERNAL_PATIENT_MISMATCH; 404 PROFESSIONAL_NOT_FOUND/HOLD_NOT_FOUND/HOLD_EXPIRED; 409 SLOT_ALREADY_RESERVED; 403 HOLD_OWNER_MISMATCH. Se guardan como CONFIRMACION_RECHAZADA. Los cuerpos externos, nombres recibidos y credenciales no se copian a los mensajes de error. Los errores de consulta del catálogo conservan el tratamiento existente.

## Pruebas

`ConfirmacionHttpTest` usa PostgreSQL Testcontainers, reloj controlado y servidores HTTP locales. Cubre aceptación sin afirmación de resultado final, identidad de paciente, propietarios, vencimientos, agenda, concurrencia, repeticiones, rechazo remoto, respuestas incompletas/ajenas, timeout real, pérdida de respuesta, interrupción durable y fallo al guardar la aceptación. No confirma holds reales en cátedra.

Ejecutar `./mvnw test`. Para empaquetar el JAR sin repetir una suite ya ejecutada sobre el mismo código, `./mvnw -DskipTests package`.

## Continuación por Kafka

El [flujo de teléfono y resultado final](FLUJO_KAFKA.md) amplía esta etapa. Una solicitud o resultado recibido durante la llamada REST se conserva y no se sobrescribe al guardar su respuesta. La repetición de confirmación devuelve también TELEFONO_ENVIADO, CONFIRMADO o PROCESO_INVALIDO cuando el proceso ya avanzó.
