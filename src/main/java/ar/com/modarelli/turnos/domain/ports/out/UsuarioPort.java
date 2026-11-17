package ar.com.modarelli.turnos.domain.ports.out;

import ar.com.modarelli.turnos.domain.model.BloqueoDtos.*;
import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;

import java.time.*;
import java.util.*;

public interface UsuarioPort {
    record Usuario(Cuenta cuenta, String passwordHash) {
        @Override
        public String toString() {
            return "Usuario[datos privados]";
        }
    }

    Optional<Usuario> porLogin(String login);

    Optional<Usuario> porIdPublico(UUID id);

    boolean existeEmail(String email);

    Cuenta crear(Registro datos, String login, String email, String hash, Instant ahora);
}
