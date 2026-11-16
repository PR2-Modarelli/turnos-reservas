package ar.com.modarelli.turnos.domain.ports.out;

import java.time.*;

public interface HoldGateway {
    void exigirConfiguracionHold();

    HoldRespuesta crearHold(HoldSolicitud solicitud);

    record HoldSolicitud(
            long professionalId, LocalDate date, LocalTime startTime, String externalPatientId) {}

    record HoldRespuesta(
            String holdId, String reservationProcessId, String status, Instant expiresAt) {}

    record ProblemaHold(String code) {}
}
