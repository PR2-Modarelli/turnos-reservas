package ar.com.modarelli.turnos.web;
import ar.com.modarelli.turnos.service.DisponibilidadServicio;
import ar.com.modarelli.turnos.service.DisponibilidadServicio.Disponibilidad;
import java.time.LocalDate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/turnos")
public class DisponibilidadResource {
    private final DisponibilidadServicio servicio;
    public DisponibilidadResource(DisponibilidadServicio servicio) { this.servicio=servicio; }
    @GetMapping("/disponibilidad")
    public Disponibilidad consultar(@RequestParam long profesionalId, @RequestParam LocalDate fecha,
                                    @AuthenticationPrincipal Jwt jwt) {
        return servicio.consultar(profesionalId,fecha,jwt.getTokenValue());
    }
}
