package ar.com.modarelli.turnos.domain.ports.out;

import java.time.Instant;

public interface ConfirmacionGateway {
    void exigirConfiguracionHold();

    RespuestaConfirmacion confirmarHold(
            String holdId, String procesoRemoto, SolicitudConfirmacion solicitud);

    record SolicitudConfirmacion(
            String externalPatientId, String patientFirstName, String patientLastName) {
        @Override
        public String toString() {
            return "SolicitudConfirmacion[datos privados]";
        }
    }

    record RespuestaConfirmacion(String reservationProcessId, String status, Instant expiresAt) {}
}
