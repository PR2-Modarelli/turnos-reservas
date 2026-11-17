package ar.com.modarelli.turnos.infrastructure.web.controller;

import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;
import ar.com.modarelli.turnos.domain.model.BloqueoDtos.Vista;
import ar.com.modarelli.turnos.domain.ports.in.ConfirmacionUseCase;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/turnos/procesos")
public class ConfirmacionResource {
    private final ConfirmacionUseCase servicio;

    public ConfirmacionResource(ConfirmacionUseCase servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/{id}/confirmacion")
    public ResponseEntity<Vista> confirmar(
            @PathVariable UUID id,
            @RequestBody(required = false) String body,
            @AuthenticationPrincipal Jwt jwt) {
        if (body != null && !body.isBlank() && !body.strip().equals("{}"))
            throw new ar.com.modarelli.turnos.domain.exception.ErrorTurnos(
                    EstadoOperacion.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "La confirmacion no acepta datos de paciente del cliente");
        return ResponseEntity.accepted()
                .location(URI.create("/api/turnos/procesos/" + id))
                .body(
                        servicio.confirmar(
                                id,
                                ar.com.modarelli.turnos.infrastructure.config.IdentidadMapper.desde(
                                        jwt)));
    }
}
