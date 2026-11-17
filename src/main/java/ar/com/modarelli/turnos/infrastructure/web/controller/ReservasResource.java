package ar.com.modarelli.turnos.infrastructure.web.controller;

import ar.com.modarelli.turnos.domain.ports.in.ReservasUseCase;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/turnos/reservas")
public class ReservasResource {
    private final ReservasUseCase servicio;

    public ReservasResource(ReservasUseCase servicio) {
        this.servicio = servicio;
    }

    public record Motivo(@Size(max = 500) String motivo) {
        @Override
        public String toString() {
            return "Motivo[oculto]";
        }
    }

    @GetMapping
    public Pagina listar(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) LocalDate desde,
            @RequestParam(required = false) LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanio,
            @AuthenticationPrincipal Jwt jwt) {
        return servicio.listar(
                estado,
                desde,
                hasta,
                pagina,
                tamanio,
                ar.com.modarelli.turnos.infrastructure.config.IdentidadMapper.desde(jwt));
    }

    @GetMapping("/{proceso}")
    public Reserva detalle(@PathVariable String proceso, @AuthenticationPrincipal Jwt jwt) {
        return servicio.detalle(
                proceso, ar.com.modarelli.turnos.infrastructure.config.IdentidadMapper.desde(jwt));
    }

    @PostMapping("/{proceso}/cancelacion")
    public ResponseEntity<Object> cancelar(
            @PathVariable String proceso,
            @Valid @RequestBody(required = false) Motivo motivo,
            @AuthenticationPrincipal Jwt jwt) {
        var r =
                servicio.cancelar(
                        proceso,
                        motivo == null ? null : motivo.motivo(),
                        ar.com.modarelli.turnos.infrastructure.config.IdentidadMapper.desde(jwt));
        return ResponseEntity.status(r.pendiente() ? 202 : 200).body(r.reserva());
    }
}
