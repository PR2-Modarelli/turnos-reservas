package ar.com.modarelli.turnos.domain.exception;

public class ConflictoCuenta extends RuntimeException {
    public ConflictoCuenta(Throwable causa) {
        super("Cuenta duplicada", causa);
    }
}
