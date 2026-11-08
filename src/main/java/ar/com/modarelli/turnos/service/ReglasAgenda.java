package ar.com.modarelli.turnos.service;

import ar.com.modarelli.turnos.integration.Contratos.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Reglas puras compartidas por disponibilidad y validacion de nuevos bloqueos. */
public final class ReglasAgenda {
    private ReglasAgenda() {}
    public static TreeMap<LocalTime,LocalTime> candidatos(Agenda a,long id,LocalDate fecha) {
        if(a==null || a.version()==null || a.version()<0 || a.profesional()==null ||
            !Long.valueOf(id).equals(a.profesional().id()) || a.profesional().habilitado()==null ||
            a.categoriaHabilitada()==null || a.horarios()==null) throw invalida();
        var candidatos=new TreeMap<LocalTime,LocalTime>();
        for(Horario h:a.horarios()) {
            if(h==null || !Long.valueOf(id).equals(h.profesionalId()) || h.diaSemana()==null || h.habilitado()==null ||
                h.horaInicio()==null || h.horaFin()==null || h.horaInicio().getNano()!=0 || h.horaFin().getNano()!=0 || !h.horaInicio().isBefore(h.horaFin()) ||
                h.duracionTurnoMinutos()==null || h.duracionTurnoMinutos()<1 || h.duracionTurnoMinutos()>1440) throw invalida();
            if(!a.profesional().habilitado() || !a.categoriaHabilitada() || !h.habilitado() || h.diaSemana()!=fecha.getDayOfWeek())continue;
            long paso=h.duracionTurnoMinutos()*60L;
            for(long inicio=h.horaInicio().toSecondOfDay();inicio+paso<=h.horaFin().toSecondOfDay();inicio+=paso) {
                LocalTime desde=LocalTime.ofSecondOfDay(inicio),hasta=LocalTime.ofSecondOfDay(inicio+paso);
                LocalTime anterior=candidatos.putIfAbsent(desde,hasta);
                if(anterior!=null && !anterior.equals(hasta))throw invalida();
            }
        }
        return candidatos;
    }
    private static ErrorTurnos invalida() {
        return new ErrorTurnos(HttpStatus.BAD_GATEWAY,"RESPUESTA_EXTERNA_INVALIDA","Una dependencia devolvio datos invalidos");
    }
}
