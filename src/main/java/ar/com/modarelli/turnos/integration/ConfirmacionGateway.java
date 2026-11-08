package ar.com.modarelli.turnos.integration;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
public interface ConfirmacionGateway {
    void exigirConfiguracionHold();
    RespuestaConfirmacion confirmarHold(String holdId,String procesoRemoto,SolicitudConfirmacion solicitud);
    record SolicitudConfirmacion(String externalPatientId,String patientFirstName,String patientLastName) {
        @Override public String toString(){return "SolicitudConfirmacion[datos privados]";}
    }
    @JsonIgnoreProperties(ignoreUnknown=true)
    record RespuestaConfirmacion(String reservationProcessId,String status,Instant expiresAt) {}
}
