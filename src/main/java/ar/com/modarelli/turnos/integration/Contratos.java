package ar.com.modarelli.turnos.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.*;
import java.util.List;

public final class Contratos {
    private Contratos() {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profesional(Long id, String nombre, String apellido, Long categoriaId, String categoria, Boolean habilitado) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Horario(Long id, Long profesionalId, DayOfWeek diaSemana, LocalTime horaInicio,
                          LocalTime horaFin, Integer duracionTurnoMinutos, Boolean habilitado) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Agenda(Long version, Profesional profesional, Boolean categoriaHabilitada, List<Horario> horarios) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ocupacion(LocalDate date, LocalTime startTime, String status, Instant expiresAt) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ocupaciones(Long professionalId, LocalDate from, LocalDate to, List<Ocupacion> occupancies) {}
}
