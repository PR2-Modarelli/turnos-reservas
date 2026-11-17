package ar.com.modarelli.turnos.infrastructure.web.controller;

import ar.com.modarelli.turnos.domain.model.DisponibilidadDtos.Disponibilidad;
import ar.com.modarelli.turnos.domain.ports.in.DisponibilidadUseCase;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/turnos")
public class DisponibilidadResource {
    private final DisponibilidadUseCase servicio;

    public DisponibilidadResource(DisponibilidadUseCase servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/disponibilidad")
    public Disponibilidad consultar(
            @RequestParam long profesionalId,
            @RequestParam LocalDate fecha,
            @AuthenticationPrincipal Jwt jwt) {
        return servicio.consultar(profesionalId, fecha, jwt.getTokenValue());
    }
}
