package ar.com.modarelli.turnos.domain.ports.out;

import java.time.*;
import java.util.*;

public interface ReservasGateway {
    Pagina listarReservas(
            String paciente,
            String estado,
            LocalDate desde,
            LocalDate hasta,
            int pagina,
            int tamanio);

    Cancelacion cancelarReserva(String proceso, Long reserva, String motivo);

    record Reserva(
            Long reservationId,
            String reservationProcessId,
            String externalPatientId,
            LocalDate appointmentDate,
            LocalTime startTime,
            LocalTime endTime,
            Long professionalId,
            String professionalFirstName,
            String professionalLastName,
            String patientFirstName,
            String patientLastName,
            String patientPhone,
            String status,
            Instant createdAt,
            Instant confirmedAt,
            Instant cancelledAt,
            String cancellationReason) {
        @Override
        public String toString() {
            return "Reserva[datos personales ocultos]";
        }
    }

    record Pagina(List<Reserva> reservas, long total, int pagina, int tamanio) {}

    record Motivo(String reason) {
        @Override
        public String toString() {
            return "Motivo[oculto]";
        }
    }

    record Cancelacion(
            Long reservationId, String reservationProcessId, String status, Instant cancelledAt) {}
}
