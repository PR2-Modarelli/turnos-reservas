package ar.com.modarelli.turnos.application.usecases;

import ar.com.modarelli.turnos.domain.ports.in.*;
import ar.com.modarelli.turnos.domain.ports.out.*;

import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class PublicarPendientesServicio implements PublicarPendientesUseCase {
    private final FlujoReservasUseCase flujo;
    private final FlujoPersistenciaPort datos;
    private final PublicarTelefonoPort publicador;
    private final Clock reloj;

    public PublicarPendientesServicio(
            FlujoReservasUseCase flujo,
            FlujoPersistenciaPort datos,
            PublicarTelefonoPort publicador,
            Clock reloj) {
        this.flujo = flujo;
        this.datos = datos;
        this.publicador = publicador;
        this.reloj = reloj;
    }

    public void publicarPendientes() {
        for (String id : datos.entradasPendientes()) flujo.reprocesar(id);
        datos.cancelarVencidos(reloj.instant());
        for (var envio : datos.salidasPendientes()) {
            try {
                publicador.publicar(envio.proceso(), envio.payload());
                datos.publicado(envio.evento(), reloj.instant());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                return;
            }
        }
    }
}
