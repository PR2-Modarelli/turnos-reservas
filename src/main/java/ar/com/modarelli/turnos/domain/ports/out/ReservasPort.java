package ar.com.modarelli.turnos.domain.ports.out;

import ar.com.modarelli.turnos.domain.model.BloqueoDtos.*;
import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;

import java.time.*;
import java.util.*;

public interface ReservasPort {
    record RegistroReserva(String estado, Long reservaId, Instant canceladoEn) {}

    RegistroReserva propio(String remoto, long usuario);

    void observar(Reserva reserva, long usuario, Instant ahora);

    boolean preparar(String remoto, long usuario, String motivo, Instant ahora);

    void cancelado(String remoto, Long reserva, Instant fecha, String motivo, Instant ahora);

    void fallo(String remoto, boolean incierto, String code, int http, Instant ahora);
}
