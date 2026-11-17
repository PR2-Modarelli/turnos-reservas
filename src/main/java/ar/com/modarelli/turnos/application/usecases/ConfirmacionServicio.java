package ar.com.modarelli.turnos.application.usecases;

import ar.com.modarelli.turnos.domain.exception.ErrorBloqueo;
import ar.com.modarelli.turnos.domain.exception.ErrorConfirmacionRemota;
import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;
import ar.com.modarelli.turnos.domain.model.BloqueoDtos.*;
import ar.com.modarelli.turnos.domain.model.IdentidadUsuario;
import ar.com.modarelli.turnos.domain.ports.in.CuentaUseCase;
import ar.com.modarelli.turnos.domain.ports.out.CatalogoGateway;
import ar.com.modarelli.turnos.domain.ports.out.ConfirmacionGateway;
import ar.com.modarelli.turnos.domain.ports.out.ConfirmacionGateway.*;
import ar.com.modarelli.turnos.domain.ports.out.ProcesoReservaPort;
import ar.com.modarelli.turnos.domain.service.ReglasAgenda;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.UUID;

@Service
public class ConfirmacionServicio
        implements ar.com.modarelli.turnos.domain.ports.in.ConfirmacionUseCase {
    private final CuentaUseCase cuentas;
    private final ProcesoReservaPort procesos;
    private final CatalogoGateway catalogo;
    private final ConfirmacionGateway catedra;
    private final Clock reloj;

    public ConfirmacionServicio(
            CuentaUseCase cuentas,
            ProcesoReservaPort procesos,
            CatalogoGateway catalogo,
            ConfirmacionGateway catedra,
            Clock reloj) {
        this.cuentas = cuentas;
        this.procesos = procesos;
        this.catalogo = catalogo;
        this.catedra = catedra;
        this.reloj = reloj;
    }

    public Vista confirmar(UUID id, IdentidadUsuario jwt) {
        var cuenta = cuentas.cuenta(jwt);
        Proceso actual = procesos.consultar(id, cuenta.id(), reloj.instant());
        if (actual.estado() != Estado.BLOQUEADO) return resultado(actual);
        String nombre = cuenta.firstName() == null ? "" : cuenta.firstName().strip();
        String apellido = cuenta.lastName() == null ? "" : cuenta.lastName().strip();
        if (nombre.length() < 2
                || nombre.length() > 100
                || apellido.length() < 2
                || apellido.length() > 100)
            throw new ErrorBloqueo(
                    EstadoOperacion.BAD_REQUEST,
                    "DATOS_PACIENTE_INCOMPLETOS",
                    "La cuenta necesita nombre y apellido validos",
                    id);
        var agenda = catalogo.agenda(actual.profesionalId(), jwt.credencialCatalogo());
        if (!ReglasAgenda.candidatos(agenda, actual.profesionalId(), actual.fecha())
                .containsKey(actual.horaInicio()))
            throw new ErrorBloqueo(
                    EstadoOperacion.BAD_REQUEST,
                    "INVALID_SLOT",
                    "La agenda vigente no admite ese turno",
                    id);
        catedra.exigirConfiguracionHold();
        var preparacion = procesos.prepararConfirmacion(id, cuenta.id(), reloj.instant());
        if (!preparacion.enviar()) return resultado(preparacion.proceso());
        RespuestaConfirmacion respuesta;
        try {
            respuesta =
                    catedra.confirmarHold(
                            actual.holdId(),
                            actual.reservationProcessId(),
                            new SolicitudConfirmacion(
                                    cuenta.publicId().toString(), nombre, apellido));
        } catch (ErrorConfirmacionRemota e) {
            procesos.fallarConfirmacion(
                    id, e.incierto(), e.code(), e.status().value(), reloj.instant());
            throw new ErrorBloqueo(
                    e.status(), e.code(), "No se pudo completar la confirmacion inicial", id);
        }
        try {
            procesos.guardarConfirmacion(id, respuesta.expiresAt(), reloj.instant());
        } catch (RuntimeException e) {
            throw new ErrorBloqueo(
                    EstadoOperacion.SERVICE_UNAVAILABLE,
                    "RESULTADO_CONFIRMACION_DESCONOCIDO",
                    "No se pudo guardar la respuesta; consultar el proceso antes de continuar",
                    id);
        }
        return resultado(procesos.consultar(id, cuenta.id(), reloj.instant()));
    }

    private Vista resultado(Proceso p) {
        return switch (p.estado()) {
            case CONFIRMANDO,
                            ESPERANDO_TELEFONO,
                            CONFIRMACION_DESCONOCIDA,
                            TELEFONO_ENVIADO,
                            CONFIRMADO,
                            PROCESO_INVALIDO,
                            CANCELANDO,
                            CANCELACION_DESCONOCIDA,
                            CANCELADO ->
                    p.vista();
            case CONFIRMACION_RECHAZADA ->
                    throw new ErrorBloqueo(
                            EstadoOperacion.valueOf(p.errorHttp()),
                            p.errorCode(),
                            "La confirmacion inicial fue rechazada",
                            p.id());
            case VENCIDO ->
                    throw new ErrorBloqueo(
                            EstadoOperacion.CONFLICT,
                            "HOLD_EXPIRED",
                            "El bloqueo esta vencido",
                            p.id());
            default ->
                    throw new ErrorBloqueo(
                            EstadoOperacion.CONFLICT,
                            "ESTADO_NO_CONFIRMABLE",
                            "El proceso no tiene un bloqueo confirmado localmente",
                            p.id());
        };
    }
}
