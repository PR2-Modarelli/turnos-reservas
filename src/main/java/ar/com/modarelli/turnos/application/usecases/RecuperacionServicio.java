package ar.com.modarelli.turnos.application.usecases;

import ar.com.modarelli.turnos.domain.ports.in.RecuperacionUseCase;
import ar.com.modarelli.turnos.domain.ports.out.*;

import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class RecuperacionServicio implements RecuperacionUseCase {
    private final RecuperacionPort procesos;
    private final ReservasGateway remoto;
    private final ReservasPort reservas;
    private final Clock clock;
    private final ar.com.modarelli.turnos.domain.ports.in.CuentaUseCase cuentas;

    public RecuperacionServicio(
            RecuperacionPort procesos,
            ReservasGateway remoto,
            ReservasPort reservas,
            Clock clock,
            ar.com.modarelli.turnos.domain.ports.in.CuentaUseCase cuentas) {
        this.procesos = procesos;
        this.remoto = remoto;
        this.reservas = reservas;
        this.clock = clock;
        this.cuentas = cuentas;
    }

    public void abandonar(
            java.util.UUID id, ar.com.modarelli.turnos.domain.model.IdentidadUsuario identidad) {
        long usuario = cuentas.cuenta(identidad).id();
        if (!procesos.abandonar(id, usuario, clock.instant()))
            throw new ar.com.modarelli.turnos.domain.exception.ErrorTurnos(
                    ar.com.modarelli.turnos.domain.exception.EstadoOperacion.CONFLICT,
                    "ABANDONO_NO_PERMITIDO",
                    "Solo puede abandonarse un intento propio en revision sin identificadores"
                        + " remotos");
    }

    public void recuperar() {
        for (var p : procesos.pendientes(clock.instant())) {
            // Persistir el intento antes de acceder a la red permite continuar tras un reinicio.
            if (!procesos.iniciar(p.id(), clock.instant())) continue;
            try {
                String resultado = "NO_VERIFICABLE";
                if (p.remoto() != null) {
                    for (int pagina = 0; pagina < 10; pagina++) {
                        var lote =
                                remoto.listarReservas(p.paciente(), null, null, null, pagina, 100);
                        var encontrada =
                                lote.reservas().stream()
                                        .filter(r -> p.remoto().equals(r.reservationProcessId()))
                                        .findFirst();
                        if (encontrada.isPresent()) {
                            var r = encontrada.get();
                            if (!p.paciente().equals(r.externalPatientId()))
                                throw new IllegalStateException("Propietario incompatible");
                            reservas.observar(r, p.usuario(), clock.instant());
                            resultado =
                                    "PHONE_PENDING".equals(r.status())
                                            ? "ESPERANDO_EVENTO"
                                            : "VERIFICADO";
                            break;
                        }
                        if ((long) (pagina + 1) * 100 >= lote.total()) break;
                        if (lote.reservas().isEmpty()) break;
                        resultado = "LIMITE_CONSULTA";
                    }
                }
                procesos.registrar(p.id(), clock.instant(), resultado);
            } catch (RuntimeException e) {
                // Un fallo externo no debe impedir recuperar otros procesos ni revelar datos
                // personales.
                procesos.registrar(p.id(), clock.instant(), "CONSULTA_FALLIDA");
            }
        }
    }
}
