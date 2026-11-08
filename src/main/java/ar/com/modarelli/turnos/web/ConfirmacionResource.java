package ar.com.modarelli.turnos.web;
import ar.com.modarelli.turnos.reservas.*;
import ar.com.modarelli.turnos.reservas.BloqueoDtos.Vista;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/turnos/procesos")
public class ConfirmacionResource {
    private final ConfirmacionServicio servicio;
    public ConfirmacionResource(ConfirmacionServicio servicio){this.servicio=servicio;}
    @PostMapping("/{id}/confirmacion")
    public ResponseEntity<Vista> confirmar(@PathVariable UUID id,@RequestBody(required=false) String body,@AuthenticationPrincipal Jwt jwt) {
        if(body!=null && !body.isBlank() && !body.strip().equals("{}"))
            throw new ar.com.modarelli.turnos.service.ErrorTurnos(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","La confirmacion no acepta datos de paciente del cliente");
        return ResponseEntity.accepted().location(URI.create("/api/turnos/procesos/"+id)).body(servicio.confirmar(id,jwt));
    }
}
