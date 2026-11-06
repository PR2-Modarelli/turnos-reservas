package ar.com.modarelli.turnos.service;

import ar.com.modarelli.turnos.integration.*;
import ar.com.modarelli.turnos.integration.Contratos.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DisponibilidadServicio {
    private final CatalogoGateway catalogo;
    private final OcupacionesGateway catedra;
    private final Clock reloj;
    public DisponibilidadServicio(CatalogoGateway catalogo, OcupacionesGateway catedra, Clock reloj) {
        this.catalogo=catalogo; this.catedra=catedra; this.reloj=reloj;
    }
    public record Turno(LocalTime horaInicio, LocalTime horaFin) {}
    public record Disponibilidad(long profesionalId, LocalDate fecha, String zonaHoraria, long versionCatalogo, List<Turno> turnos) {}

    public Disponibilidad consultar(long id, LocalDate fecha, String jwt) {
        if (id<1 || fecha==null || fecha.isBefore(LocalDate.now(reloj)) || fecha.isAfter(LocalDate.now(reloj).plusDays(90)))
            throw new ErrorTurnos(HttpStatus.BAD_REQUEST, "CONSULTA_INVALIDA", "La fecha debe estar entre hoy y los proximos 90 dias y el ID debe ser positivo");
        Agenda agenda=catalogo.agenda(id,jwt);
        validarAgenda(agenda,id);
        var candidatos=new TreeMap<LocalTime,LocalTime>();
        if (agenda.profesional().habilitado() && agenda.categoriaHabilitada()) {
            for (Horario h:agenda.horarios()) {
                if (!h.habilitado() || h.diaSemana()!=fecha.getDayOfWeek()) continue;
                // Se opera en segundos para impedir el wrap de LocalTime al cruzar medianoche.
                long paso=h.duracionTurnoMinutos()*60L;
                for (long inicio=h.horaInicio().toSecondOfDay(); inicio+paso<=h.horaFin().toSecondOfDay(); inicio+=paso) {
                    LocalTime desde=LocalTime.ofSecondOfDay(inicio), hasta=LocalTime.ofSecondOfDay(inicio+paso);
                    LocalTime anterior=candidatos.putIfAbsent(desde,hasta);
                    if (anterior!=null && !anterior.equals(hasta)) throw invalida();
                }
            }
        }
        // Sin reglas aplicables no hace falta consultar ocupaciones.
        if (!candidatos.isEmpty()) {
            Ocupaciones ocupaciones=catedra.consultar(id,fecha);
            validarOcupaciones(ocupaciones,id,fecha);
            // El contrato define ocupaciones por fecha+hora. Se excluyen HELD y CONFIRMED,
            // incluso si el TTL parece cumplido: la catedra decide su vigencia real.
            ocupaciones.occupancies().forEach(o -> candidatos.remove(o.startTime()));
        }
        var ahora=LocalDateTime.now(reloj);
        List<Turno> libres=candidatos.entrySet().stream()
            .filter(e -> LocalDateTime.of(fecha,e.getKey()).isAfter(ahora))
            .map(e -> new Turno(e.getKey(),e.getValue())).toList();
        return new Disponibilidad(id,fecha,reloj.getZone().getId(),agenda.version(),libres);
    }
    private void validarAgenda(Agenda a, long id) {
        if (a==null || a.version()==null || a.version()<0 || a.profesional()==null ||
            !Long.valueOf(id).equals(a.profesional().id()) || a.profesional().habilitado()==null ||
            a.categoriaHabilitada()==null || a.horarios()==null) throw invalida();
        for (Horario h:a.horarios()) {
            if (h==null || !Long.valueOf(id).equals(h.profesionalId()) || h.diaSemana()==null || h.habilitado()==null ||
                h.horaInicio()==null || h.horaFin()==null || !h.horaInicio().isBefore(h.horaFin()) ||
                h.duracionTurnoMinutos()==null || h.duracionTurnoMinutos()<1 || h.duracionTurnoMinutos()>1440)
                throw invalida();
        }
    }
    private void validarOcupaciones(Ocupaciones o, long id, LocalDate fecha) {
        if (o==null || !Long.valueOf(id).equals(o.professionalId()) || !fecha.equals(o.from()) ||
            !fecha.equals(o.to()) || o.occupancies()==null) throw invalida();
        for (Ocupacion item:o.occupancies()) {
            if (item==null || !fecha.equals(item.date()) || item.startTime()==null ||
                !("CONFIRMED".equals(item.status()) || "HELD".equals(item.status())) ||
                ("HELD".equals(item.status()) && item.expiresAt()==null)) throw invalida();
        }
    }
    private ErrorTurnos invalida() {
        return new ErrorTurnos(HttpStatus.BAD_GATEWAY,"RESPUESTA_EXTERNA_INVALIDA","Una dependencia devolvio datos invalidos");
    }
}
