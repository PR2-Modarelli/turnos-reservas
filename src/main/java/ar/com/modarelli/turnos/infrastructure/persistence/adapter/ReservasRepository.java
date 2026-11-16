package ar.com.modarelli.turnos.infrastructure.persistence.adapter;

import ar.com.modarelli.turnos.domain.exception.ErrorTurnos;
import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;
import ar.com.modarelli.turnos.domain.ports.out.ReservasPort;
import ar.com.modarelli.turnos.domain.ports.out.ReservasPort.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Repository
public class ReservasRepository implements ReservasPort {
    private final JdbcTemplate jdbc;

    public ReservasRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Map<String, Object> propioFila(String remoto, long usuario) {
        return jdbc
                .queryForList(
                        "select * from proceso_reserva where reservation_process_id=? and"
                            + " usuario_id=?",
                        remoto,
                        usuario)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new ErrorTurnos(
                                        EstadoOperacion.NOT_FOUND,
                                        "RESERVA_NO_ENCONTRADA",
                                        "Reserva inexistente"));
    }

    public RegistroReserva propio(String remoto, long usuario) {
        var p = propioFila(remoto, usuario);
        var fecha = (Timestamp) p.get("cancelado_en");
        return new RegistroReserva(
                (String) p.get("estado"),
                p.get("reserva_id") == null ? null : ((Number) p.get("reserva_id")).longValue(),
                fecha == null ? null : fecha.toInstant());
    }

    @Transactional
    public void observar(Reserva r, long usuario, Instant ahora) {
        var filas =
                jdbc.queryForList(
                        "select * from proceso_reserva where reservation_process_id=? for update",
                        r.reservationProcessId());
        if (filas.isEmpty()) return;
        var p = filas.getFirst();
        if (((Number) p.get("usuario_id")).longValue() != usuario
                || ((Number) p.get("profesional_id")).longValue() != r.professionalId()
                || !((java.sql.Date) p.get("fecha")).toLocalDate().equals(r.appointmentDate())
                || !((java.sql.Time) p.get("hora_inicio")).toLocalTime().equals(r.startTime())
                || (p.get("reserva_id") != null
                        && ((Number) p.get("reserva_id")).longValue() != r.reservationId()))
            throw new ErrorTurnos(
                    EstadoOperacion.BAD_GATEWAY,
                    "RESPUESTA_EXTERNA_INVALIDA",
                    "La reserva no coincide con el proceso local");
        if ("CANCELLED".equals(r.status()))
            cancelado(
                    r.reservationProcessId(),
                    r.reservationId(),
                    r.cancelledAt(),
                    r.cancellationReason(),
                    ahora);
        else if ("FAILED".equals(r.status()))
            jdbc.update(
                    "update proceso_reserva set"
                        + " estado='PROCESO_INVALIDO',reserva_id=?,error_code='RESERVA_FALLIDA',error_http=null,actualizado_en=?"
                        + " where id=? and estado not in"
                        + " ('CANCELADO','CANCELANDO','CANCELACION_DESCONOCIDA')",
                    r.reservationId(),
                    Timestamp.from(ahora),
                    p.get("id"));
        else if ("CONFIRMED".equals(r.status()))
            jdbc.update(
                    "update proceso_reserva set"
                        + " estado='CONFIRMADO',reserva_id=?,error_code=null,error_http=null,actualizado_en=?"
                        + " where id=? and estado not in"
                        + " ('CANCELADO','CANCELANDO','CANCELACION_DESCONOCIDA')",
                    r.reservationId(),
                    Timestamp.from(ahora),
                    p.get("id"));
    }

    @Transactional
    public boolean preparar(String remoto, long usuario, String motivo, Instant ahora) {
        jdbc.queryForList(
                "select id from proceso_reserva where reservation_process_id=? and usuario_id=? for"
                    + " update",
                remoto,
                usuario);
        var p = propioFila(remoto, usuario);
        String estado = (String) p.get("estado");
        if ("CANCELADO".equals(estado)) return false;
        Timestamp inicio = (Timestamp) p.get("cancelacion_iniciada_en");
        if ("CANCELANDO".equals(estado)
                && inicio != null
                && inicio.toInstant().isAfter(ahora.minusSeconds(30))) return false;
        if (!Set.of("CONFIRMADO", "CANCELANDO", "CANCELACION_DESCONOCIDA").contains(estado))
            throw new ErrorTurnos(
                    EstadoOperacion.CONFLICT,
                    "RESERVATION_NOT_CANCELLABLE",
                    "La reserva no admite cancelacion");
        jdbc.update(
                "update proceso_reserva set"
                    + " estado='CANCELANDO',cancelacion_iniciada_en=?,motivo_cancelacion=?,error_code=null,error_http=null,actualizado_en=?"
                    + " where id=?",
                Timestamp.from(ahora),
                motivo,
                Timestamp.from(ahora),
                p.get("id"));
        return true;
    }

    @Transactional
    public void cancelado(
            String remoto, Long reserva, Instant fecha, String motivo, Instant ahora) {
        jdbc.update(
                "update proceso_reserva set"
                    + " estado='CANCELADO',reserva_id=?,cancelado_en=?,motivo_cancelacion=?,error_code=null,error_http=null,actualizado_en=?"
                    + " where reservation_process_id=?",
                reserva,
                Timestamp.from(fecha),
                motivo,
                Timestamp.from(ahora),
                remoto);
        jdbc.update(
                "update telefono_salida set cancelado=true where proceso_id in (select id from"
                    + " proceso_reserva where reservation_process_id=?) and publicado_en is null",
                remoto);
    }

    public void fallo(String remoto, boolean incierto, String code, int http, Instant ahora) {
        jdbc.update(
                "update proceso_reserva set estado=?,error_code=?,error_http=?,actualizado_en=?"
                    + " where reservation_process_id=? and estado='CANCELANDO'",
                incierto ? "CANCELACION_DESCONOCIDA" : "CONFIRMADO",
                code,
                http,
                Timestamp.from(ahora),
                remoto);
    }
}
