package ar.com.modarelli.turnos.domain.ports.out;

import ar.com.modarelli.turnos.domain.model.BloqueoDtos.*;
import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;

import java.time.*;
import java.util.*;

public interface ProcesoReservaPort {
    record PreparacionConfirmacion(Proceso proceso, boolean enviar) {}

    Inicio iniciar(long usuario, UUID clave, Solicitud solicitud, Instant ahora);

    Proceso consultar(UUID id, long usuario, Instant ahora);

    Proceso buscar(UUID id, long usuario);

    boolean marcarEnvio(UUID id, Instant ahora);

    void rechazar(UUID id, String code, int http, Instant ahora);

    void desconocido(UUID id, Instant ahora);

    void guardarHold(UUID id, String hold, String remoto, Instant vence, Instant ahora);

    PreparacionConfirmacion prepararConfirmacion(UUID id, long usuario, Instant ahora);

    void guardarConfirmacion(UUID id, Instant vence, Instant ahora);

    void fallarConfirmacion(UUID id, boolean incierta, String code, int http, Instant ahora);
}
