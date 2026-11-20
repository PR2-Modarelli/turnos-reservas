package ar.com.modarelli.turnos.application.usecases;

import ar.com.modarelli.turnos.domain.exception.ErrorTurnos;
import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;
import ar.com.modarelli.turnos.domain.model.EventoTurnos;
import ar.com.modarelli.turnos.domain.model.IdentidadUsuario;
import ar.com.modarelli.turnos.domain.model.ProcesoFlujo;
import ar.com.modarelli.turnos.domain.ports.in.CuentaUseCase;
import ar.com.modarelli.turnos.domain.ports.out.CodecEventosPort;
import ar.com.modarelli.turnos.domain.ports.out.FlujoPersistenciaPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class FlujoKafkaServicio
        implements ar.com.modarelli.turnos.domain.ports.in.FlujoReservasUseCase {
    private final FlujoPersistenciaPort datos;
    private final CuentaUseCase cuentas;
    private final Clock reloj;
    private final CodecEventosPort json;
    private final String grupo;
    private final boolean habilitado;

    public FlujoKafkaServicio(
            FlujoPersistenciaPort datos,
            CodecEventosPort json,
            CuentaUseCase cuentas,
            Clock reloj,
            @Value("${turnos.kafka.group-id:}") String grupo,
            @Value("${turnos.kafka.enabled:false}") boolean habilitado) {
        this.datos = datos;
        this.json = json;
        this.cuentas = cuentas;
        this.reloj = reloj;
        this.grupo = grupo;
        this.habilitado = habilitado;
    }

    private static boolean texto(String s) {
        return s != null && !s.isBlank() && s.length() <= 200;
    }

    private ErrorTurnos error(EstadoOperacion h, String c, String m) {
        return new ErrorTurnos(h, c, m);
    }

    // Inbox y transicion en una misma transaccion. El offset solo se confirma despues del commit.
    @Transactional
    public void recibir(String key, String payload) {
        EventoTurnos e;
        try {
            e = json.leer(payload);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Evento de turnos ilegible", ex);
        }
        if (e.schemaVersion() == null
                || e.schemaVersion() != 1
                || !texto(e.eventId())
                || !texto(e.reservationProcessId())
                || !Objects.equals(key, e.reservationProcessId())
                || !texto(e.holdId())
                || e.occurredAt() == null)
            throw new IllegalArgumentException("Sobre de evento de turnos invalido");
        if (e.groupId() != null && !grupo.equals(e.groupId()))
            throw new IllegalArgumentException("Grupo de evento incorrecto");
        int nuevo =
                datos.registrarEntrada(
                        e.eventId(), e.reservationProcessId(), payload, reloj.instant());
        if (nuevo == 0) return;
        aplicar(e);
    }

    @Transactional
    public void reprocesar(String id) {
        var filas = datos.entradaPendiente(id);
        if (!filas.isEmpty()) {
            datos.marcarIntento(reloj.instant(), id);
            aplicar(json.leer(filas.getFirst()));
        }
    }

    private void terminar(EventoTurnos e, String error) {
        datos.marcarProcesado(error, e.eventId());
    }

    private void aplicar(EventoTurnos e) {
        var filas = datos.porCorrelacion(e.reservationProcessId(), e.holdId());
        if (filas.isEmpty()) return; // Puede adelantarse al guardado de la respuesta del hold.
        if (filas.size() != 1) throw new IllegalStateException("Correlacion de proceso ambigua");
        var p = filas.getFirst();
        UUID id = p.id();
        String estado = p.estado();
        if ("ABANDONADO_LOCAL".equals(estado)) {
            terminar(e, "ABANDONO_LOCAL");
            return;
        }
        if ("AppointmentCancelled".equals(e.eventType())) {
            if ("CANCELADO".equals(estado)) {
                terminar(e, null);
                return;
            }
            if (!grupo.equals(e.groupId())
                    || e.reservationId() == null
                    || e.reservationId() <= 0
                    || !"CANCELLED".equals(e.status())
                    || !Objects.equals(e.professionalId(), p.profesionalId())
                    || !Objects.equals(e.date(), p.fecha())
                    || !Objects.equals(e.startTime(), p.horaInicio())
                    || (p.reservaId() != null && !Objects.equals(p.reservaId(), e.reservationId()))
                    || (e.reason() != null && e.reason().length() > 500)) {
                terminar(e, "CANCELACION_NO_COINCIDE");
                return;
            }
            String request = p.solicitudId();
            if (request == null) return;
            if (!request.equals(e.requestEventId())) {
                terminar(e, "SOLICITUD_NO_COINCIDE");
                return;
            }
            datos.guardarCancelacion(
                    e.reservationId(), e.occurredAt(), e.reason(), reloj.instant(), id);
            datos.cancelarSalidas(id);
            terminar(e, null);
            return;
        }
        if (Set.of(
                        "CONFIRMADO",
                        "VENCIDO",
                        "PROCESO_INVALIDO",
                        "CANCELANDO",
                        "CANCELACION_DESCONOCIDA",
                        "CANCELADO")
                .contains(estado)) {
            terminar(e, null);
            return;
        }
        String solicitud = p.solicitudId();
        switch (e.eventType() == null ? "" : e.eventType()) {
            case "AdditionalInformationRequested" -> {
                if (!"PHONE_NUMBER".equals(e.requiredField()) || e.expiresAt() == null) {
                    terminar(e, "SOLICITUD_INVALIDA");
                    return;
                }
                if (!Set.of(
                                "BLOQUEADO",
                                "CONFIRMANDO",
                                "CONFIRMACION_DESCONOCIDA",
                                "ESPERANDO_TELEFONO")
                        .contains(estado)) {
                    terminar(e, "ESTADO_INCOMPATIBLE");
                    return;
                }
                // Una solicitud ya correlacionada no se reemplaza por una entrega atrasada.
                if (solicitud != null) {
                    terminar(e, solicitud.equals(e.eventId()) ? null : "SOLICITUD_DISTINTA");
                    return;
                }
                datos.guardarSolicitud(
                        e.eventId(), e.occurredAt(), e.expiresAt(), reloj.instant(), id);
            }
            case "AppointmentConfirmed",
                    "AdditionalInformationRejected",
                    "AppointmentProcessExpired",
                    "AppointmentProcessInvalid" -> {
                if (!grupo.equals(e.groupId()) || !texto(e.requestEventId())) {
                    terminar(e, "CORRELACION_INVALIDA");
                    return;
                }
                if (solicitud == null)
                    return; // Guardar hasta recibir la solicitud, incluso si se altera el orden.
                if (!solicitud.equals(e.requestEventId())) {
                    terminar(e, "SOLICITUD_NO_COINCIDE");
                    return;
                }
                var fechaSolicitud = p.solicitudEn();
                if (fechaSolicitud != null && e.occurredAt().isBefore(fechaSolicitud)) {
                    terminar(e, "EVENTO_ANTERIOR");
                    return;
                }
                if ("AppointmentConfirmed".equals(e.eventType())) {
                    if (e.reservationId() == null
                            || e.reservationId() <= 0
                            || !Objects.equals(e.professionalId(), p.profesionalId())
                            || !Objects.equals(e.date(), p.fecha())
                            || !Objects.equals(e.startTime(), p.horaInicio())
                            || !"CONFIRMED".equals(e.status())) {
                        terminar(e, "RESERVA_NO_COINCIDE");
                        return;
                    }
                    datos.guardarConfirmacion(e.reservationId(), reloj.instant(), id);
                } else if ("AdditionalInformationRejected".equals(e.eventType())) {
                    if (e.expiresAt() == null || (!texto(e.reason()) || e.reason().length() > 80)) {
                        terminar(e, "RECHAZO_INVALIDO");
                        return;
                    }
                    // Un rechazo habilita una correccion; nunca se vuelve a publicar el mismo
                    // intento.
                    datos.guardarRechazo(e.expiresAt(), e.reason(), reloj.instant(), id);
                } else {
                    if ((!texto(e.reason()) || e.reason().length() > 80)) {
                        terminar(e, "RESULTADO_INVALIDO");
                        return;
                    }
                    datos.guardarResultado(
                            "AppointmentProcessExpired".equals(e.eventType())
                                    ? "VENCIDO"
                                    : "PROCESO_INVALIDO",
                            e.reason(),
                            reloj.instant(),
                            id);
                }
                datos.cancelarSalidas(id);
            }
            default -> {
                terminar(e, "TIPO_NO_SOPORTADO");
                return;
            }
        }
        terminar(e, null);
    }

    @Transactional
    public Map<String, Object> informacion(UUID id, IdentidadUsuario jwt) {
        long usuario = cuentas.cuenta(jwt).id();
        var p = propio(id, usuario);
        Map<String, Object> vista = new LinkedHashMap<>();
        vista.put("id", id);
        vista.put("estado", p.estado());
        vista.put("recuperacion", datos.recuperacion(id));
        vista.put("requestEventId", p.solicitudId());
        vista.put("reservationId", p.reservaId());
        vista.put("ultimoError", p.ultimoError());
        Instant vence = p.venceEn();
        vista.put("expiresAt", vence == null ? null : vence);
        vista.put(
                "requiereTelefono",
                "ESPERANDO_TELEFONO".equals(p.estado())
                        && p.solicitudId() != null
                        && vence != null
                        && vence.isAfter(reloj.instant()));
        return vista;
    }

    private ProcesoFlujo propio(UUID id, long usuario) {
        return datos.propio(id, usuario).stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                error(
                                        EstadoOperacion.NOT_FOUND,
                                        "PROCESO_NO_ENCONTRADO",
                                        "Proceso inexistente"));
    }

    @Transactional
    public Map<String, Object> telefono(
            UUID id, UUID clave, String telefono, IdentidadUsuario jwt) {
        long usuario = cuentas.cuenta(jwt).id();
        var p = propio(id, usuario);
        if (telefono == null || telefono.length() > 100)
            throw error(EstadoOperacion.BAD_REQUEST, "VALIDATION_ERROR", "Telefono invalido");
        String normal = telefono.replaceAll("[ ().-]", "");
        if (!normal.matches("\\+?[0-9]{7,15}"))
            throw error(EstadoOperacion.BAD_REQUEST, "INVALID_PHONE_NUMBER", "Telefono invalido");
        var anterior = datos.telefonos(id, clave);
        if (!anterior.isEmpty()) {
            if (!normal.equals(anterior.getFirst()))
                throw error(
                        EstadoOperacion.CONFLICT,
                        "IDEMPOTENCY_KEY_REUTILIZADA",
                        "La clave ya identifica otro telefono");
            return informacion(id, jwt);
        }
        if (!habilitado || grupo.isBlank())
            throw error(
                    EstadoOperacion.SERVICE_UNAVAILABLE,
                    "KAFKA_NO_CONFIGURADO",
                    "Falta configurar el intercambio Kafka");
        Instant vence = p.venceEn();
        if (!"ESPERANDO_TELEFONO".equals(p.estado()) || p.solicitudId() == null)
            throw error(
                    EstadoOperacion.CONFLICT,
                    "TELEFONO_NO_SOLICITADO",
                    "El proceso no admite un nuevo envio de telefono");
        if (vence == null || !vence.isAfter(reloj.instant()))
            throw error(
                    EstadoOperacion.CONFLICT,
                    "PROCESO_VENCIDO",
                    "El plazo para enviar telefono vencio");
        UUID evento = UUID.randomUUID();
        Instant ahora = reloj.instant();
        String payload =
                json.escribir(
                        Map.of(
                                "eventId",
                                evento.toString(),
                                "eventType",
                                "AdditionalInformationSubmitted",
                                "schemaVersion",
                                1,
                                "groupId",
                                grupo,
                                "reservationProcessId",
                                p.procesoRemoto(),
                                "holdId",
                                p.holdId(),
                                "requestEventId",
                                p.solicitudId(),
                                "information",
                                Map.of("phoneNumber", normal),
                                "occurredAt",
                                ahora));
        datos.encolarTelefono(evento, id, clave, normal, payload, ahora);
        datos.marcarTelefono(ahora, id);
        return informacion(id, jwt);
    }
}
