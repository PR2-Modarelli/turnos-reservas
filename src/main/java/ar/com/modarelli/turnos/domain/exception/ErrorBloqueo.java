package ar.com.modarelli.turnos.domain.exception;

import java.util.UUID;

public class ErrorBloqueo extends ErrorTurnos {
    private final UUID procesoId;

    public ErrorBloqueo(EstadoOperacion status, String code, String mensaje, UUID procesoId) {
        super(status, code, mensaje);
        this.procesoId = procesoId;
    }

    public UUID procesoId() {
        return procesoId;
    }
}
