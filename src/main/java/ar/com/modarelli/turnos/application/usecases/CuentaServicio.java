package ar.com.modarelli.turnos.application.usecases;

import ar.com.modarelli.turnos.domain.exception.ConflictoCuenta;
import ar.com.modarelli.turnos.domain.exception.ErrorTurnos;
import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;
import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;
import ar.com.modarelli.turnos.domain.model.IdentidadUsuario;
import ar.com.modarelli.turnos.domain.ports.out.ProteccionCredenciales;
import ar.com.modarelli.turnos.domain.ports.out.UsuarioPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class CuentaServicio implements ar.com.modarelli.turnos.domain.ports.in.CuentaUseCase {
    private final UsuarioPort usuarios;
    private final ProteccionCredenciales passwords;

    private final Clock reloj;
    private final String hashInexistente;

    public CuentaServicio(UsuarioPort usuarios, ProteccionCredenciales passwords, Clock reloj) {
        this.usuarios = usuarios;
        this.passwords = passwords;
        this.reloj = reloj;
        hashInexistente = passwords.cifrar(UUID.randomUUID().toString());
    }

    @Transactional
    public Cuenta registrar(Registro datos) {
        String login = datos.login().toLowerCase(Locale.ROOT);
        String email = datos.email().strip().toLowerCase(Locale.ROOT);
        if (usuarios.porLogin(login).isPresent()) throw duplicado("USERNAME_ALREADY_EXISTS");
        if (usuarios.existeEmail(email)) throw duplicado("EMAIL_ALREADY_EXISTS");
        try {
            return usuarios.crear(
                    datos, login, email, passwords.cifrar(datos.password()), reloj.instant());
        } catch (ConflictoCuenta e) {
            // UNIQUE tambien protege dos registros simultaneos; no se expone SQL ni datos
            // personales.
            throw duplicado("ACCOUNT_ALREADY_EXISTS");
        }
    }

    public Token autenticar(Credenciales datos) {
        String username = datos.username().strip().toLowerCase(Locale.ROOT);
        var usuario = usuarios.porLogin(username);
        // Siempre ejecutar el mismo hash, incluso si el usuario no existe.
        boolean correcta =
                passwords.verificar(
                        datos.password(),
                        usuario.map(UsuarioPort.Usuario::passwordHash).orElse(hashInexistente));
        if (usuario.isEmpty() || !correcta || !usuario.get().cuenta().activated())
            throw noAutorizado();
        Cuenta cuenta = usuario.get().cuenta();
        Instant ahora = reloj.instant();
        return new Token(
                passwords.emitir(
                        cuenta,
                        ahora,
                        ahora.plusSeconds(Boolean.TRUE.equals(datos.rememberMe()) ? 86400 : 3600)));
    }

    @Transactional(readOnly = true)
    public Cuenta cuenta(IdentidadUsuario jwt) {
        try {
            var usuario = usuarios.porIdPublico(UUID.fromString(jwt.uid()));
            if (usuario.isEmpty()
                    || !usuario.get().cuenta().activated()
                    || !usuario.get().cuenta().login().equals(jwt.subject())) throw noAutorizado();
            return usuario.get().cuenta();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw noAutorizado();
        }
    }

    private ErrorTurnos duplicado(String code) {
        return new ErrorTurnos(EstadoOperacion.BAD_REQUEST, code, "La cuenta ya existe");
    }

    private ErrorTurnos noAutorizado() {
        return new ErrorTurnos(
                EstadoOperacion.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "No se pudo autenticar al usuario");
    }
}
