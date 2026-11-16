package ar.com.modarelli.turnos.domain.model;

import java.time.*;
import java.util.UUID;

public record ProcesoFlujo(
        UUID id,
        String estado,
        long profesionalId,
        LocalDate fecha,
        LocalTime horaInicio,
        String solicitudId,
        Instant solicitudEn,
        Long reservaId,
        Instant venceEn,
        String ultimoError,
        String procesoRemoto,
        String holdId) {}
