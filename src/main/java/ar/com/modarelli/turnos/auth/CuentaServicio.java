package ar.com.modarelli.turnos.auth;

import ar.com.modarelli.turnos.auth.CuentaDtos.*;
import ar.com.modarelli.turnos.service.ErrorTurnos;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CuentaServicio {
    private final UsuarioRepository usuarios;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final Clock reloj;
    private final String hashInexistente;
    public CuentaServicio(UsuarioRepository usuarios, PasswordEncoder passwords, JwtEncoder encoder, Clock reloj) {
        this.usuarios=usuarios;this.passwords=passwords;this.encoder=encoder;this.reloj=reloj;
        hashInexistente=passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional
    public Cuenta registrar(Registro datos) {
        String login=datos.login().toLowerCase(Locale.ROOT);
        String email=datos.email().strip().toLowerCase(Locale.ROOT);
        if (usuarios.porLogin(login).isPresent()) throw duplicado("USERNAME_ALREADY_EXISTS");
        if (usuarios.existeEmail(email)) throw duplicado("EMAIL_ALREADY_EXISTS");
        try {
            return usuarios.crear(datos,login,email,passwords.encode(datos.password()),reloj.instant());
        } catch (DataIntegrityViolationException e) {
            // UNIQUE tambien protege dos registros simultaneos; no se expone SQL ni datos personales.
            throw duplicado("ACCOUNT_ALREADY_EXISTS");
        }
    }
    public Token autenticar(Credenciales datos) {
        String username=datos.username().strip().toLowerCase(Locale.ROOT);
        var usuario=usuarios.porLogin(username);
        // Siempre ejecutar el mismo hash, incluso si el usuario no existe.
        boolean correcta=passwords.matches(datos.password(),usuario.map(UsuarioRepository.Usuario::passwordHash).orElse(hashInexistente));
        if (usuario.isEmpty() || !correcta || !usuario.get().cuenta().activated()) throw noAutorizado();
        Cuenta cuenta=usuario.get().cuenta();
        Instant ahora=reloj.instant();
        var claims=JwtClaimsSet.builder().subject(cuenta.login()).issuedAt(ahora)
            .expiresAt(ahora.plusSeconds(Boolean.TRUE.equals(datos.rememberMe())?86400:3600))
            .claim("auth","ROLE_USER").claim("uid",cuenta.publicId().toString()).build();
        return new Token(encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS512).build(),claims)).getTokenValue());
    }
    @Transactional(readOnly=true)
    public Cuenta cuenta(Jwt jwt) {
        try {
            var usuario=usuarios.porIdPublico(UUID.fromString(jwt.getClaimAsString("uid")));
            if (usuario.isEmpty() || !usuario.get().cuenta().activated() ||
                !usuario.get().cuenta().login().equals(jwt.getSubject())) throw noAutorizado();
            return usuario.get().cuenta();
        } catch (IllegalArgumentException | NullPointerException e) { throw noAutorizado(); }
    }
    private ErrorTurnos duplicado(String code) { return new ErrorTurnos(HttpStatus.BAD_REQUEST,code,"La cuenta ya existe"); }
    private ErrorTurnos noAutorizado() { return new ErrorTurnos(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","No se pudo autenticar al usuario"); }
}
