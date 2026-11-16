package ar.com.modarelli.turnos.application.usecases;

import ar.com.modarelli.turnos.domain.exception.ErrorTurnos;
import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;
import ar.com.modarelli.turnos.domain.model.IdentidadUsuario;
import ar.com.modarelli.turnos.domain.model.ResultadoCancelacion;
import ar.com.modarelli.turnos.domain.ports.in.CuentaUseCase;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;
import ar.com.modarelli.turnos.domain.ports.out.ReservasPort;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class ReservasServicio implements ar.com.modarelli.turnos.domain.ports.in.ReservasUseCase {
    private final CuentaUseCase cuentas;
    private final ReservasGateway remoto;
    private final ReservasPort repositorio;
    private final Clock reloj;

    public ReservasServicio(
            CuentaUseCase cuentas, ReservasGateway remoto, ReservasPort repositorio, Clock reloj) {
        this.cuentas = cuentas;
        this.remoto = remoto;
        this.repositorio = repositorio;
        this.reloj = reloj;
    }

    public Pagina listar(
            String estado,
            LocalDate desde,
            LocalDate hasta,
            int pagina,
            int tamanio,
            IdentidadUsuario jwt) {
        var cuenta = cuentas.cuenta(jwt);
        if (pagina < 0
                || tamanio < 1
                || tamanio > 100
                || pagina > 100000
                || (desde != null && hasta != null && desde.isAfter(hasta))
                || (estado != null
                        && !Set.of("PHONE_PENDING", "CONFIRMED", "CANCELLED", "FAILED")
                                .contains(estado)))
            throw new ErrorTurnos(
                    EstadoOperacion.BAD_REQUEST, "VALIDATION_ERROR", "Filtros invalidos");
        var respuesta =
                remoto.listarReservas(
                        cuenta.publicId().toString(), estado, desde, hasta, pagina, tamanio);
        for (var reserva : respuesta.reservas())
            repositorio.observar(reserva, cuenta.id(), reloj.instant());
        return respuesta;
    }

    public Reserva detalle(String proceso, IdentidadUsuario jwt) {
        var cuenta = cuentas.cuenta(jwt);
        repositorio.propio(proceso, cuenta.id());
        // El contrato no tiene GET individual ni filtro por proceso. Buscar solo dentro del
        // paciente actual.
        for (int pagina = 0; pagina < 100; pagina++) {
            var r =
                    remoto.listarReservas(
                            cuenta.publicId().toString(), null, null, null, pagina, 100);
            for (var reserva : r.reservas())
                if (proceso.equals(reserva.reservationProcessId())) {
                    repositorio.observar(reserva, cuenta.id(), reloj.instant());
                    return reserva;
                }
            if ((long) (pagina + 1) * 100 >= r.total())
                throw new ErrorTurnos(
                        EstadoOperacion.NOT_FOUND, "RESERVA_NO_ENCONTRADA", "Reserva inexistente");
            if (r.reservas().isEmpty())
                throw new ErrorTurnos(
                        EstadoOperacion.BAD_GATEWAY,
                        "RESPUESTA_EXTERNA_INVALIDA",
                        "Paginacion externa inconsistente");
        }
        throw new ErrorTurnos(
                EstadoOperacion.SERVICE_UNAVAILABLE,
                "LIMITE_CONSULTA_RESERVAS",
                "No se pudo completar la busqueda de la reserva");
    }

    public ResultadoCancelacion cancelar(String proceso, String motivo, IdentidadUsuario jwt) {
        var cuenta = cuentas.cuenta(jwt);
        repositorio.propio(proceso, cuenta.id());
        Reserva actual = detalle(proceso, jwt);
        if ("CANCELLED".equals(actual.status()))
            return new ResultadoCancelacion(
                    false,
                    new Cancelacion(
                            actual.reservationId(), proceso, "CANCELLED", actual.cancelledAt()));
        if (!"CONFIRMED".equals(actual.status()))
            throw new ErrorTurnos(
                    EstadoOperacion.CONFLICT,
                    "RESERVATION_NOT_CANCELLABLE",
                    "La reserva no admite cancelacion");
        if (!repositorio.preparar(proceso, cuenta.id(), motivo, reloj.instant())) {
            var guardado = repositorio.propio(proceso, cuenta.id());
            if ("CANCELADO".equals(guardado.estado()))
                return new ResultadoCancelacion(
                        false,
                        new Cancelacion(
                                guardado.reservaId(),
                                proceso,
                                "CANCELLED",
                                guardado.canceladoEn()));
            return new ResultadoCancelacion(
                    true, Map.of("reservationProcessId", proceso, "estado", guardado.estado()));
        }
        Cancelacion respuesta;
        try {
            respuesta = remoto.cancelarReserva(proceso, actual.reservationId(), motivo);
        } catch (ErrorTurnos e) {
            boolean incierto = e.status().is5xxServerError();
            repositorio.fallo(proceso, incierto, e.code(), e.status().value(), reloj.instant());
            throw e;
        }
        try {
            repositorio.cancelado(
                    proceso,
                    respuesta.reservationId(),
                    respuesta.cancelledAt(),
                    motivo,
                    reloj.instant());
        } catch (RuntimeException e) {
            throw new ErrorTurnos(
                    EstadoOperacion.SERVICE_UNAVAILABLE,
                    "RESULTADO_CANCELACION_DESCONOCIDO",
                    "No se pudo guardar la cancelacion; consultar la reserva");
        }
        return new ResultadoCancelacion(false, respuesta);
    }
}
