package ar.com.modarelli.turnos.infrastructure.web.controller;

import ar.com.modarelli.turnos.domain.ports.in.RecuperacionUseCase;
import ar.com.modarelli.turnos.infrastructure.config.IdentidadMapper;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/turnos/procesos")
public class RecuperacionResource {
    private final RecuperacionUseCase recuperacion;

    public RecuperacionResource(RecuperacionUseCase recuperacion) {
        this.recuperacion = recuperacion;
    }

    @PostMapping("/{id}/abandono-local")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abandonar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        recuperacion.abandonar(id, IdentidadMapper.desde(jwt));
    }
}
