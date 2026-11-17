package ar.com.modarelli.turnos.domain.ports.out;

import ar.com.modarelli.turnos.domain.model.CuentaDtos.Cuenta;

import java.time.Instant;

public interface ProteccionCredenciales {
    String cifrar(String clave);

    boolean verificar(String clave, String hash);

    String emitir(Cuenta cuenta, Instant ahora, Instant vence);
}
