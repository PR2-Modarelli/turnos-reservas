package ar.com.modarelli.turnos.integration;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.*;
public interface HoldGateway {
    void exigirConfiguracionHold();
    HoldRespuesta crearHold(HoldSolicitud solicitud);
    record HoldSolicitud(long professionalId, LocalDate date, LocalTime startTime, String externalPatientId) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    record HoldRespuesta(String holdId, String reservationProcessId, String status, Instant expiresAt) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    record ProblemaHold(String code) {}
}
