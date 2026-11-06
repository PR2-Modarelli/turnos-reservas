package ar.com.modarelli.turnos.web;

import ar.com.modarelli.turnos.auth.CuentaServicio;
import ar.com.modarelli.turnos.auth.CuentaDtos.*;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CuentaResource {
    private final CuentaServicio servicio;
    public CuentaResource(CuentaServicio servicio) { this.servicio=servicio; }
    @PostMapping("/register")
    public ResponseEntity<Cuenta> registrar(@Valid @RequestBody Registro registro) {
        return ResponseEntity.created(URI.create("/api/account")).body(servicio.registrar(registro));
    }
    @PostMapping("/authenticate")
    public ResponseEntity<Token> login(@Valid @RequestBody Credenciales datos) {
        Token token=servicio.autenticar(datos);
        return ResponseEntity.ok().header(HttpHeaders.AUTHORIZATION,"Bearer "+token.id_token())
            .cacheControl(CacheControl.noStore()).body(token);
    }
    @GetMapping("/account")
    public Cuenta cuenta(@AuthenticationPrincipal Jwt jwt) { return servicio.cuenta(jwt); }
}
