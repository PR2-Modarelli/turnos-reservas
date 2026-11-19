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
Una operación en curso tiene un margen de 30 segundos; cada proceso espera al
menos 60 segundos entre consultas. El intento se registra antes de acceder a la
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

Los casos sin evidencia requieren revisión o una ampliación del contrato de la
cátedra. El sistema continúa consultando con el límite y espera indicados; no
hay un número máximo de ciclos que convierta la incertidumbre en un fracaso.

## Diagnóstico

`proceso_reserva.recuperacion_en` y `recuperacion_resultado` registran el último
intento: `EN_CURSO`, `VERIFICADO`, `ESPERANDO_EVENTO`, `NO_VERIFICABLE`,
`LIMITE_CONSULTA` o `CONSULTA_FALLIDA`. Son datos internos, sin teléfono ni token.
`VERIFICADO` indica que se encontró evidencia remota; las transiciones finales
siguen protegidas contra retrocesos y pueden conservar un estado más avanzado.
