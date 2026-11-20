# Recuperación de procesos pendientes

La recuperación automática se activa al iniciar con `bash scripts/iniciar-local.sh`.
`TURNOS_RECUPERACION_ENABLED=false` permite desactivarla. En ejecuciones directas
con Maven se activa con esa variable o con `turnos.recuperacion.enabled=true`.
El intervalo y la demora inicial son de 60 segundos, configurables mediante
`turnos.recuperacion.intervalo-ms`. Las pruebas la ejecutan explícitamente.

## Qué recupera

Un adaptador programado invoca `RecuperacionUseCase`. El caso de uso consulta los
puertos de persistencia y de reservas externas, sin acceder a SQL, HTTP ni Kafka.
Los procesos y sus intentos permanecen en PostgreSQL después de un reinicio.

Se revisan hasta 20 procesos por ciclo, ordenados por el intento más antiguo.
Una operación en curso tiene un margen de 30 segundos; cada proceso espera inicialmente
60 segundos entre consultas. La espera se duplica hasta un máximo de una hora. El intento se registra antes de acceder a la
red. Una caída durante la consulta permite retomarla en un ciclo posterior.
Los errores de una consulta no impiden revisar los siguientes procesos.

La identidad se obtiene de la cuenta asociada al proceso, nunca del cliente.
La consulta usa `GET /api/appointments` filtrado por esa identidad y busca el
`reservationProcessId` persistido, hasta 10 páginas de 100 reservas por proceso.
Antes de aplicar el resultado se comprueban propietario, profesional, fecha,
hora e identificador de reserva. La actualización toma un bloqueo de fila,
compartido con las actualizaciones por Kafka.

- `CONFIRMED` permite recuperar una confirmación cuyo resultado se perdió.
- `CANCELLED` recupera la cancelación, su fecha original y cancela salidas pendientes.
- `FAILED` cierra un proceso pendiente como inválido.
- `PHONE_PENDING` espera el evento Kafka que contiene la correlación de teléfono;
  no inventa `requestEventId` ni vuelve a confirmar por REST.
- Una lectura anterior no hace retroceder un proceso confirmado, cancelado,
  vencido o inválido. La cancelación confirmada sí puede cerrar un confirmado.

Los envíos pendientes de teléfono y eventos recibidos siguen usando la bandeja
persistente existente. La recuperación no crea salidas nuevas ni repite POST de
bloqueo, confirmación o cancelación.

Un proceso `CREADO` interrumpido antes del envío se rechaza tras 30 segundos.
`SOLICITANDO`, `CONFIRMANDO` y `CANCELANDO` interrumpidos se consideran de resultado
desconocido. Un bloqueo conocido vence únicamente según su vencimiento guardado;
ese vencimiento no se aplica a una confirmación aceptada o incierta.

## Límites del contrato

Si se perdió la respuesta al crear el bloqueo y no quedaron identificadores
remotos, no existe un endpoint para recuperarlos. No se intenta asociar otra
reserva por coincidencia de paciente, profesional y horario. La ausencia en un
listado, su paginación incompleta o una caída de red tampoco prueban rechazo ni
vencimiento: el resultado permanece sin verificar. No se hacen reintentos de
operaciones con efectos basados en esa ausencia.

Los casos sin evidencia pasan a revisión al alcanzar el máximo de intentos
(default 6). Si faltan identificadores remotos se suspenden desde la primera
revisión, sin consultar la red. Se conserva el estado desconocido y todos los
identificadores; agotar los intentos no demuestra rechazo ni vencimiento.
El contador y la próxima fecha se guardan antes de consultar. Un reinicio consume
ese intento y respeta su espera. La toma del intento es atómica entre trabajadores.

`TURNOS_RECUPERACION_MAX_INTENTOS` admite 1..100; la espera inicial
`TURNOS_RECUPERACION_ESPERA_SEGUNDOS` admite 1..3600. Se usan también en el inbox.
En Compose se configuran en `deploy/private/.env`; en ejecución local, en `.env`.
Un evento válido posterior o una consulta REST del propietario puede aportar
el resultado definitivo aunque las consultas automáticas se hayan suspendido.
Una nueva solicitud válida de teléfono constituye progreso y reinicia el presupuesto.

## Abandono local de un bloqueo sin identificadores

`POST /api/turnos/procesos/{id}/abandono-local` requiere JWT del propietario y
solo admite `RESULTADO_DESCONOCIDO` en revisión, sin `holdId` ni identificador
remoto. Devuelve 204; repetir el abandono propio también devuelve 204. Los demás
casos devuelven 409 sin revelar información de otros usuarios.

El estado `ABANDONADO_LOCAL` libera la restricción local del horario y conserva
el registro y su clave de idempotencia. No significa cancelación ni fallo remoto:
no se envía ningún POST a la cátedra. El cliente puede decidir iniciar otra
solicitud con otra clave; la cátedra puede rechazarla si todavía existe un hold.
Una respuesta HTTP tardía conserva los identificadores para auditoría, pero no
reactiva la confirmación de ese intento abandonado. No se permite abandonar
confirmaciones o cancelaciones inciertas, que sí podrían haber producido una reserva.

## Eventos sin correlación

El inbox guarda intentos, próxima fecha y `requiere_revision`. Tras 6 revisiones
sin aplicación se detiene el sondeo de ese evento. Si aparece el proceso remoto
o cambia su evidencia local (`actualizado_en`), se concede una nueva oportunidad
al evento, incluso suspendido. Se registra la versión temporal ya revisada para
no repetir indefinidamente la misma correlación. Los eventos procesados no vuelven
a aplicarse. No se borran mensajes ni se generan envíos de teléfono nuevos.

## Información al propietario

`GET /api/turnos/procesos/{id}/informacion` incluye `recuperacion` con
`intentos`, `proxima`, `revision` y `resultado`. La consulta exige ser propietario.
Una respuesta definitiva tardía deja de mostrar revisión, conservando el contador
como auditoría. Las consultas de diagnóstico SQL permiten revisar también el inbox.

## Diagnóstico

`proceso_reserva.recuperacion_en` y `recuperacion_resultado` registran el último
intento: `EN_CURSO`, `VERIFICADO`, `ESPERANDO_EVENTO`, `NO_VERIFICABLE`,
`LIMITE_CONSULTA` o `CONSULTA_FALLIDA`. Son datos internos, sin teléfono ni token.
`VERIFICADO` indica que se encontró evidencia remota; las transiciones finales
siguen protegidas contra retrocesos y pueden conservar un estado más avanzado.
