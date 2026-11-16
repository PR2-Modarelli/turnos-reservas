package ar.com.modarelli.turnos.application.usecases;

import ar.com.modarelli.turnos.domain.exception.ErrorTurnos;
import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;
import ar.com.modarelli.turnos.domain.model.Contratos.*;
import ar.com.modarelli.turnos.domain.model.DisponibilidadDtos.*;
import ar.com.modarelli.turnos.domain.ports.out.CatalogoGateway;
import ar.com.modarelli.turnos.domain.ports.out.OcupacionesGateway;
import ar.com.modarelli.turnos.domain.service.ReglasAgenda;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class DisponibilidadServicio
        implements ar.com.modarelli.turnos.domain.ports.in.DisponibilidadUseCase {
    private final CatalogoGateway catalogo;
    private final OcupacionesGateway catedra;
    private final Clock reloj;

    public DisponibilidadServicio(
            CatalogoGateway catalogo, OcupacionesGateway catedra, Clock reloj) {
        this.catalogo = catalogo;
        this.catedra = catedra;
        this.reloj = reloj;
    }

    public Disponibilidad consultar(long id, LocalDate fecha, String jwt) {
        if (id < 1
                || fecha == null
                || fecha.isBefore(LocalDate.now(reloj))
                || fecha.isAfter(LocalDate.now(reloj).plusDays(90)))
            throw new ErrorTurnos(
                    EstadoOperacion.BAD_REQUEST,
                    "CONSULTA_INVALIDA",
                    "La fecha debe estar entre hoy y los proximos 90 dias y el ID debe ser"
                        + " positivo");
        Agenda agenda = catalogo.agenda(id, jwt);
        var candidatos = ReglasAgenda.candidatos(agenda, id, fecha);
        // Sin reglas aplicables no hace falta consultar ocupaciones.
        if (!candidatos.isEmpty()) {
            Ocupaciones ocupaciones = catedra.consultar(id, fecha);
            validarOcupaciones(ocupaciones, id, fecha);
            // El contrato define ocupaciones por fecha+hora. Se excluyen HELD y CONFIRMED,
            // incluso si el TTL parece cumplido: la catedra decide su vigencia real.
            ocupaciones.occupancies().forEach(o -> candidatos.remove(o.startTime()));
        }
        var ahora = LocalDateTime.now(reloj);
        List<Turno> libres =
                candidatos.entrySet().stream()
                        .filter(e -> LocalDateTime.of(fecha, e.getKey()).isAfter(ahora))
                        .map(e -> new Turno(e.getKey(), e.getValue()))
                        .toList();
        return new Disponibilidad(id, fecha, reloj.getZone().getId(), agenda.version(), libres);
    }

    private void validarOcupaciones(Ocupaciones o, long id, LocalDate fecha) {
        if (o == null
                || !Long.valueOf(id).equals(o.professionalId())
                || !fecha.equals(o.from())
                || !fecha.equals(o.to())
                || o.occupancies() == null) throw invalida();
        for (Ocupacion item : o.occupancies()) {
            if (item == null
                    || !fecha.equals(item.date())
                    || item.startTime() == null
                    || !("CONFIRMED".equals(item.status()) || "HELD".equals(item.status()))
                    || ("HELD".equals(item.status()) && item.expiresAt() == null)) throw invalida();
        }
    }

    private ErrorTurnos invalida() {
        return new ErrorTurnos(
                EstadoOperacion.BAD_GATEWAY,
                "RESPUESTA_EXTERNA_INVALIDA",
                "Una dependencia devolvio datos invalidos");
    }
}
