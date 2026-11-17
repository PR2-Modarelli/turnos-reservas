package ar.com.modarelli.turnos.infrastructure.web.controller;

import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;
import ar.com.modarelli.turnos.domain.model.BloqueoDtos.*;
import ar.com.modarelli.turnos.domain.ports.in.BloqueoUseCase;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/turnos")
public class BloqueoResource {
    private final BloqueoUseCase servicio;

    public BloqueoResource(BloqueoUseCase servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/bloqueos")
    public ResponseEntity<Vista> crear(
            @RequestHeader("Idempotency-Key") UUID clave,
            @Valid @RequestBody
                    ar.com.modarelli.turnos.infrastructure.web.dto.BloqueoRequest solicitud,
            @AuthenticationPrincipal Jwt jwt) {
        Resultado resultado =
                servicio.crear(
                        clave,
                        new Solicitud(
                                solicitud.profesionalId(),
                                solicitud.fecha(),
                                solicitud.horaInicio()),
                        ar.com.modarelli.turnos.infrastructure.config.IdentidadMapper.desde(jwt));
        Vista p = resultado.proceso();
        EstadoOperacion status =
                (p.estado() == Estado.CREADO
                                || p.estado() == Estado.SOLICITANDO
                                || p.estado() == Estado.RESULTADO_DESCONOCIDO)
                        ? EstadoOperacion.ACCEPTED
                        : (resultado.creado() ? EstadoOperacion.CREATED : EstadoOperacion.OK);
        return ResponseEntity.status(status.value())
                .location(URI.create("/api/turnos/procesos/" + p.id()))
                .body(p);
    }

    @GetMapping("/procesos/{id}")
    public Vista consultar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return servicio.consultar(
                id, ar.com.modarelli.turnos.infrastructure.config.IdentidadMapper.desde(jwt));
    }
}
