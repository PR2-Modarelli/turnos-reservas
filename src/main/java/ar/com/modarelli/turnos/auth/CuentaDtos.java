package ar.com.modarelli.turnos.auth;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CuentaDtos {
    private CuentaDtos() {}
    public record Registro(
        @NotBlank @Size(min=3,max=50) @Pattern(regexp="[_.@A-Za-z0-9-]+") String login,
        @NotNull @Size(min=4,max=100) String password,
        @Size(max=50) String firstName,
        @Size(max=50) String lastName,
        @NotBlank @Email @Size(min=5,max=254) String email,
        @Size(max=256) String imageUrl,
        @Size(min=2,max=10) String langKey
    ) {
        @Override public String toString() { return "Registro[datos privados]"; }
    }
    public record Credenciales(
        @NotBlank @Size(max=254) String username,
        @NotNull @Size(min=4,max=100) String password,
        Boolean rememberMe
    ) {
        @Override public String toString() { return "Credenciales[datos privados]"; }
    }
    public record Cuenta(Long id, UUID publicId, String login, String firstName, String lastName,
                         String email, String imageUrl, String langKey, boolean activated,
                         List<String> authorities, Instant createdDate) {}
    public record Token(String id_token) {
        @Override public String toString() { return "Token[privado]"; }
    }
}
