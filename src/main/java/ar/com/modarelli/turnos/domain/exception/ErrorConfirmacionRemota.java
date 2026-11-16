package ar.com.modarelli.turnos.domain.exception;


public class ErrorConfirmacionRemota extends RuntimeException {
    private final boolean incierto;
    private final EstadoOperacion status;
    private final String code;

    public ErrorConfirmacionRemota(boolean incierto, EstadoOperacion status, String code) {
        super("No se pudo completar la confirmacion inicial");
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
