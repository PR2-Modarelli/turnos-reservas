package ar.com.modarelli.turnos.reservas;
import ar.com.modarelli.turnos.service.ErrorTurnos;
import java.util.UUID;
import org.springframework.http.HttpStatus;
public class ErrorBloqueo extends ErrorTurnos {
    private final UUID procesoId;
    public ErrorBloqueo(HttpStatus status,String code,String mensaje,UUID procesoId) {
        super(status,code,mensaje);this.procesoId=procesoId;
    }
    public UUID procesoId() { return procesoId; }
}
