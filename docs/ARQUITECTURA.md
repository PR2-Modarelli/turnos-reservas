# Organización y arquitectura

La estructura sigue el ejemplo docente `dqmdz/hexagonal` (archivo de referencia `hexagonal-main.zip`, commit `6def11da98237b34312b3a5de201e01c351c4644`). Cada backend constituye un contexto independiente y conserva su propia base y configuración.

```text
ar/com/modarelli/turnos/
  domain/
    model/           Modelos propios e información de los procesos
    ports/in/        Interfaces de los casos de uso
    ports/out/       Interfaces de persistencia e integración
    service/         Reglas que trabajan con modelos propios
    exception/       Errores propios, sin dependencias de Spring
  application/
    usecases/        Coordinación de los casos de uso a través de puertos
  infrastructure/
    web/             Controladores, validación HTTP y traducción de errores
    persistence/     Adaptadores de almacenamiento y mapeo
    http/, kafka/, config/
```

## Dirección de las dependencias

Los modelos y puertos del dominio no importan Spring, JPA, SQL, Jackson ni implementaciones de infraestructura. Los casos de uso dependen de esos puertos. Los adaptadores implementan puertos de salida o llaman puertos de entrada; la inyección de Spring conecta las implementaciones.

Como en el ejemplo docente, la aplicación usa anotaciones de Spring para registrar servicios y delimitar transacciones cuando es necesario. Eso no introduce JWT, consultas SQL, entidades JPA ni serialización JSON en los modelos o contratos del dominio. No se requiere un módulo Maven por capa para mantener estas reglas.

El controlador valida el formato de una solicitud y la adapta a los modelos de entrada. Las reglas de negocio y la coordinación viven en dominio/aplicación; HTTP, credenciales, representación JSON y acceso a datos se resuelven en infraestructura. Las entidades de persistencia no se usan como modelos de los casos de uso.

## Comprobación de los límites

Las reglas de ArchUnit verifican que:

- El dominio no dependa de aplicación, infraestructura ni frameworks.
- La aplicación no dependa de adaptadores, JDBC/JPA, HTTP, JWT o Jackson.
- Los controladores y tareas programadas no dependan de implementaciones concretas de casos de uso.
- Los tipos superiores de los paquetes de puertos sean interfaces.

Las pruebas funcionales e integración siguen verificando el comportamiento público y los contratos de persistencia. Las pruebas usan recursos aislados de la cátedra.

## Recorridos de turnos

| Operación | Entrada | Aplicación | Puertos de salida / adaptadores |
| --- | --- | --- | --- |
| Usuarios | `CuentaResource` → `CuentaUseCase` | `CuentaServicio` | `UsuarioPort` → `UsuarioRepository`; `ProteccionCredenciales` → `ProteccionCredencialesAdapter` |
| Disponibilidad | `DisponibilidadResource` → `DisponibilidadUseCase` | `DisponibilidadServicio`, reglas `ReglasAgenda` | `CatalogoGateway`, `OcupacionesGateway` → `ClientesHttp` |
| Bloqueos | `BloqueoResource` → `BloqueoUseCase` | `BloqueoServicio` | `ProcesoReservaPort` → `ProcesoReservaRepository`; `HoldGateway` → `ClientesHttp` |
| Confirmación | `ConfirmacionResource` → `ConfirmacionUseCase` | `ConfirmacionServicio` | `ConfirmacionGateway` → `ClientesHttp` |
| Teléfono y eventos | `TelefonoResource`, `TurnosKafkaWorker` → `FlujoReservasUseCase` | `FlujoKafkaServicio` | `FlujoPersistenciaPort` → `JdbcFlujoAdapter`; `CodecEventosPort` → `JsonEventosAdapter` |
| Publicar pendientes | tarea de `TurnosKafkaWorker` → `PublicarPendientesUseCase` | `PublicarPendientesServicio` | `PublicarTelefonoPort` → `KafkaTelefonoAdapter` y almacenamiento de pendientes |
| Reservas propias | `ReservasResource` → `ReservasUseCase` | `ReservasServicio` | `ReservasGateway` → `ClientesHttp`; `ReservasPort` → `ReservasRepository` |

`IdentidadMapper` transforma el JWT validado en `IdentidadUsuario` en el borde web. Los casos de uso reciben identidad propia; la credencial se conserva opaca únicamente para el puerto de consulta de catálogo. La emisión JWT y el cifrado de contraseñas son responsabilidad del adaptador de seguridad.

Los modelos de entrada web de registro, login y bloqueo están separados de los modelos puros. Las consultas SQL y la conversión entre valores JDBC y modelos propios están en adaptadores. El estado durable, las transacciones cortas y las operaciones HTTP fuera de las transacciones conservan el comportamiento anterior.

Las interfaces `Gateway` describen contratos de integración; los objetos que intercambian no llevan anotaciones de Jackson. El adaptador HTTP y el codec de Kafka configuran la compatibilidad con campos adicionales del contrato externo.
