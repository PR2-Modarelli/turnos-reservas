package ar.com.modarelli.turnos.domain.ports.in;

public interface RecuperacionUseCase {
    void recuperar();

    void abandonar(
            java.util.UUID id, ar.com.modarelli.turnos.domain.model.IdentidadUsuario identidad);
}
