package ar.com.modarelli.turnos.domain.model;

import java.time.*;
import java.util.List;

public final class Contratos {
    private Contratos() {}

    public record Profesional(
            Long id,
            String nombre,
            String apellido,
            Long categoriaId,
            String categoria,
            Boolean habilitado) {}

    public record Horario(
            Long id,
            Long profesionalId,
            DayOfWeek diaSemana,
            LocalTime horaInicio,
            LocalTime horaFin,
            Integer duracionTurnoMinutos,
            Boolean habilitado) {}

    public record Agenda(
            Long version,
            Profesional profesional,
            Boolean categoriaHabilitada,
            List<Horario> horarios) {}

    public record Ocupacion(
            LocalDate date, LocalTime startTime, String status, Instant expiresAt) {}

    public record Ocupaciones(
            Long professionalId, LocalDate from, LocalDate to, List<Ocupacion> occupancies) {}
}
