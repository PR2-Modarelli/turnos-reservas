package ar.com.modarelli.turnos.infrastructure.config;

import ar.com.modarelli.turnos.domain.model.CuentaDtos.Cuenta;
import ar.com.modarelli.turnos.domain.ports.out.ProteccionCredenciales;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ProteccionCredencialesAdapter implements ProteccionCredenciales {
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;

    public ProteccionCredencialesAdapter(PasswordEncoder passwords, JwtEncoder encoder) {
        this.passwords = passwords;
        this.encoder = encoder;
    }

    public String cifrar(String clave) {
        return passwords.encode(clave);
    }

    public boolean verificar(String clave, String hash) {
        return passwords.matches(clave, hash);
    }

    public String emitir(Cuenta cuenta, Instant ahora, Instant vence) {
        var claims =
                JwtClaimsSet.builder()
                        .subject(cuenta.login())
                        .issuedAt(ahora)
                        .expiresAt(vence)
                        .claim("auth", "ROLE_USER")
                        .claim("uid", cuenta.publicId().toString())
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS512).build(), claims))
                .getTokenValue();
    }
}
