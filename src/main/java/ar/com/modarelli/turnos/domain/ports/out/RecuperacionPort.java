package ar.com.modarelli.turnos.domain.ports.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RecuperacionPort {
    record Pendiente(UUID id, long usuario, String paciente, String remoto) {}

    List<Pendiente> pendientes(Instant ahora);

    boolean abandonar(UUID id, long usuario, Instant ahora);

    boolean iniciar(UUID id, Instant ahora);

    void registrar(UUID id, Instant ahora, String resultado);
}
