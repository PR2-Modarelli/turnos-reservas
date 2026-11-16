package ar.com.modarelli.turnos.domain.exception;

public enum EstadoOperacion {
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    BAD_GATEWAY(502),
    SERVICE_UNAVAILABLE(503),
    INTERNAL_SERVER_ERROR(500),
    OK(200),
    CREATED(201),
    ACCEPTED(202);
    private final int value;

    EstadoOperacion(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public boolean is5xxServerError() {
        return value >= 500;
    }

    public static EstadoOperacion valueOf(int value) {
        for (var e : values()) if (e.value == value) return e;
        throw new IllegalArgumentException("Estado no admitido");
    }
}
