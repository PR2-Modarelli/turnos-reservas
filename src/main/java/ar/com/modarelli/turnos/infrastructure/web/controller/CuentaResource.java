package ar.com.modarelli.turnos.infrastructure.web.controller;

import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;
import ar.com.modarelli.turnos.domain.ports.in.CuentaUseCase;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api")
public class CuentaResource {
    private final CuentaUseCase servicio;

    public CuentaResource(CuentaUseCase servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/register")
    public ResponseEntity<Cuenta> registrar(
            @Valid @RequestBody
                    ar.com.modarelli.turnos.infrastructure.web.dto.CuentaRequests.Registro
                            registro) {
        return ResponseEntity.created(URI.create("/api/account"))
                .body(
                        servicio.registrar(
                                new Registro(
                                        registro.login(),
                                        registro.password(),
                                        registro.firstName(),
                                        registro.lastName(),
                                        registro.email(),
                                        registro.imageUrl(),
                                        registro.langKey())));
    }

    @PostMapping("/authenticate")
    public ResponseEntity<Token> login(
            @Valid @RequestBody
                    ar.com.modarelli.turnos.infrastructure.web.dto.CuentaRequests.Credenciales
                            datos) {
        Token token =
                servicio.autenticar(
                        new Credenciales(datos.username(), datos.password(), datos.rememberMe()));
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.id_token())
                .cacheControl(CacheControl.noStore())
                .body(token);
    }

    @GetMapping("/account")
    public Cuenta cuenta(@AuthenticationPrincipal Jwt jwt) {
        return servicio.cuenta(
                ar.com.modarelli.turnos.infrastructure.config.IdentidadMapper.desde(jwt));
    }
}
