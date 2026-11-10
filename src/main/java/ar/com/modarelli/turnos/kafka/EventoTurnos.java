package ar.com.modarelli.turnos.kafka;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.*;
@JsonIgnoreProperties(ignoreUnknown=true)
public record EventoTurnos(String eventId,String eventType,Integer schemaVersion,String reservationProcessId,
 String holdId,String groupId,String requestEventId,String requiredField,String message,String reason,
 Instant expiresAt,Instant occurredAt,Long reservationId,Long professionalId,LocalDate date,LocalTime startTime,String status) {}
