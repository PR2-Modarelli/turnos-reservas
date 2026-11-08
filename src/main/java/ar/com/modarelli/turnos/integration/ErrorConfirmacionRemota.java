package ar.com.modarelli.turnos.integration;
import org.springframework.http.HttpStatus;
public class ErrorConfirmacionRemota extends RuntimeException {
    private final boolean incierto;
    private final HttpStatus status;
    private final String code;
    public ErrorConfirmacionRemota(boolean incierto,HttpStatus status,String code) {
        super("No se pudo completar la confirmacion inicial");this.incierto=incierto;this.status=status;this.code=code;
    }
    public boolean incierto(){return incierto;}
    public HttpStatus status(){return status;}
    public String code(){return code;}
}
