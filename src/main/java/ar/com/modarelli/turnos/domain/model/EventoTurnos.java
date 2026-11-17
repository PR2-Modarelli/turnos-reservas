package ar.com.modarelli.turnos.domain.model;

import java.time.*;

public record EventoTurnos(
        String eventId,
        String eventType,
        Integer schemaVersion,
        String reservationProcessId,
        String holdId,
        String groupId,
        String requestEventId,
        String requiredField,
        String message,
        String reason,
        Instant expiresAt,
        Instant occurredAt,
        Long reservationId,
        Long professionalId,
        LocalDate date,
        LocalTime startTime,
        String status) {}
