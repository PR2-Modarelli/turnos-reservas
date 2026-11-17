package ar.com.modarelli.turnos.infrastructure.config;

import ar.com.modarelli.turnos.domain.model.IdentidadUsuario;

import org.springframework.security.oauth2.jwt.Jwt;

public final class IdentidadMapper {
    private IdentidadMapper() {}

    public static IdentidadUsuario desde(Jwt jwt) {
        return new IdentidadUsuario(
                jwt.getSubject(), jwt.getClaimAsString("uid"), jwt.getTokenValue());
    }
}
