package ar.com.modarelli.turnos.web;

import ar.com.modarelli.turnos.reservas.*;
import ar.com.modarelli.turnos.reservas.BloqueoDtos.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/turnos")
public class BloqueoResource {
    private final BloqueoServicio servicio;
    public BloqueoResource(BloqueoServicio servicio) { this.servicio=servicio; }
    @PostMapping("/bloqueos")
    public ResponseEntity<Vista> crear(@RequestHeader("Idempotency-Key") UUID clave,
                                      @Valid @RequestBody Solicitud solicitud,@AuthenticationPrincipal Jwt jwt) {
        Resultado resultado=servicio.crear(clave,solicitud,jwt);
        Vista p=resultado.proceso();
        HttpStatus status=(p.estado()==Estado.CREADO || p.estado()==Estado.SOLICITANDO || p.estado()==Estado.RESULTADO_DESCONOCIDO)
            ?HttpStatus.ACCEPTED:(resultado.creado()?HttpStatus.CREATED:HttpStatus.OK);
        return ResponseEntity.status(status).location(URI.create("/api/turnos/procesos/"+p.id())).body(p);
    }
    @GetMapping("/procesos/{id}")
    public Vista consultar(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) { return servicio.consultar(id,jwt); }
}
