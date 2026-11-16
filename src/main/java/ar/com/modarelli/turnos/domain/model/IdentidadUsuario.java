package ar.com.modarelli.turnos.domain.model;

public record IdentidadUsuario(String subject, String uid, String credencialCatalogo) {
    @Override
    public String toString() {
        return "IdentidadUsuario[credencial oculta]";
    }
}
