package ar.com.modarelli.turnos.reservas;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.UUID;

public final class BloqueoDtos {
    private BloqueoDtos() {}
    public record Solicitud(@NotNull @Positive Long profesionalId, @NotNull LocalDate fecha,
                            @NotNull LocalTime horaInicio) {}
    public enum Estado { CREADO, SOLICITANDO, BLOQUEADO, RECHAZADO, RESULTADO_DESCONOCIDO, VENCIDO, CONFIRMANDO, ESPERANDO_TELEFONO, CONFIRMACION_DESCONOCIDA, CONFIRMACION_RECHAZADA }
    public record Proceso(UUID id, long usuarioId, UUID clave, long profesionalId, LocalDate fecha,
                          LocalTime horaInicio, Estado estado, String holdId, String reservationProcessId,
                          Instant expiresAt, String errorCode, Integer errorHttp, Instant creadoEn, Instant actualizadoEn, Instant confirmacionExpiresAt) {
        public Vista vista() {
            return new Vista(id,profesionalId,fecha,horaInicio,estado,holdId,reservationProcessId,expiresAt,errorCode,confirmacionExpiresAt);
        }
    }
    // Ni propietario ni identidad tecnica pueden elegirse o modificarse desde la API.
    public record Vista(UUID id, long profesionalId, LocalDate fecha, LocalTime horaInicio, Estado estado,
                        String holdId, String reservationProcessId, Instant expiresAt, String ultimoError, Instant confirmacionExpiresAt) {}
    public record Inicio(Proceso proceso, boolean nuevo) {}
    public record Resultado(Vista proceso, boolean creado) {}
}
