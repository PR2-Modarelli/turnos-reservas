package ar.com.modarelli.turnos.infrastructure.web.dto;

import jakarta.validation.constraints.*;

public final class CuentaRequests {
    private CuentaRequests() {}

    public record Registro(
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[_.@A-Za-z0-9-]+") String login,
            @NotNull @Size(min = 4, max = 100) String password,
            @Size(max = 50) String firstName,
            @Size(max = 50) String lastName,
            @NotBlank @Email @Size(min = 5, max = 254) String email,
            @Size(max = 256) String imageUrl,
            @Size(min = 2, max = 10) String langKey) {
        @Override
        public String toString() {
            return "Registro[datos privados]";
        }
    }

    public record Nombres(
            @NotBlank @Size(max = 50) String firstName, @NotBlank @Size(max = 50) String lastName) {
        @Override
        public String toString() {
            return "Nombres[datos privados]";
        }
    }

    public record Credenciales(
            @NotBlank @Size(max = 254) String username,
            @NotNull @Size(min = 4, max = 100) String password,
            Boolean rememberMe) {
        @Override
        public String toString() {
            return "Credenciales[datos privados]";
        }
    }
}
