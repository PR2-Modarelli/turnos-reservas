package ar.com.modarelli.turnos.domain.exception;


public class ErrorHoldRemoto extends RuntimeException {
    private final boolean incierto;
    private final EstadoOperacion status;
    private final String code;

    public ErrorHoldRemoto(boolean incierto, EstadoOperacion status, String code) {
        super("No se pudo completar la solicitud de bloqueo");
        this.incierto = incierto;
        this.status = status;
        this.code = code;
    }

    public boolean incierto() {
        return incierto;
    }

    public EstadoOperacion status() {
        return status;
    }

    public String code() {
        return code;
    }
}
