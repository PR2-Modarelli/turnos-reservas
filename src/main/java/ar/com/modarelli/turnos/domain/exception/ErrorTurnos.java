package ar.com.modarelli.turnos.domain.exception;


public class ErrorTurnos extends RuntimeException {
    private final EstadoOperacion status;
    private final String code;

    public ErrorTurnos(EstadoOperacion status, String code, String mensaje) {
        super(mensaje);
        this.status = status;
        this.code = code;
    }

    public EstadoOperacion status() {
        return status;
    }

    public String code() {
        return code;
    }
}
