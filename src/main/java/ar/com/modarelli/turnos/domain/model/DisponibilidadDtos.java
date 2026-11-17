package ar.com.modarelli.turnos.domain.model;

import java.time.*;
import java.util.*;

public final class DisponibilidadDtos {
    public record Turno(LocalTime horaInicio, LocalTime horaFin) {}

    public record Disponibilidad(
            long profesionalId,
            LocalDate fecha,
            String zonaHoraria,
            long versionCatalogo,
            List<Turno> turnos) {}
}
