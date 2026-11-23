package ar.com.modarelli.turnos.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CuentaDtos {
    private CuentaDtos() {}

    public record Registro(
            String login,
            String password,
            String firstName,
            String lastName,
            String email,
            String imageUrl,
            String langKey) {
        @Override
        public String toString() {
            return "Registro[datos privados]";
        }
    }

    public record Nombres(String firstName, String lastName) {
        @Override
        public String toString() {
            return "Nombres[datos privados]";
        }
    }

    public record Credenciales(String username, String password, Boolean rememberMe) {
        @Override
        public String toString() {
            return "Credenciales[datos privados]";
        }
    }

    public record Cuenta(
            Long id,
            UUID publicId,
            String login,
            String firstName,
            String lastName,
            String email,
            String imageUrl,
            String langKey,
            boolean activated,
            List<String> authorities,
            Instant createdDate) {}

    public record Token(String id_token) {
        @Override
        public String toString() {
            return "Token[privado]";
        }
    }
}
