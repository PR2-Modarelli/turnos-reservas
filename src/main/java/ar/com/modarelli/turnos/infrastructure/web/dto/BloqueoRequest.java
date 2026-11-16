package ar.com.modarelli.turnos.infrastructure.web.dto;

import jakarta.validation.constraints.*;

import java.time.*;

public record BloqueoRequest(
        @NotNull @Positive Long profesionalId,
        @NotNull LocalDate fecha,
        @NotNull LocalTime horaInicio) {}
