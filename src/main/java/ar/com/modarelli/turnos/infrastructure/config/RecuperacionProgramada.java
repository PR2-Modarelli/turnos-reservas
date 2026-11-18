package ar.com.modarelli.turnos.infrastructure.config;

import ar.com.modarelli.turnos.domain.ports.in.RecuperacionUseCase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@org.springframework.scheduling.annotation.EnableScheduling
@Component
@ConditionalOnProperty(name = "turnos.recuperacion.enabled", havingValue = "true")
public class RecuperacionProgramada {
    private final RecuperacionUseCase recuperacion;

    public RecuperacionProgramada(RecuperacionUseCase recuperacion) {
        this.recuperacion = recuperacion;
    }

    @Scheduled(
            initialDelayString = "${turnos.recuperacion.intervalo-ms:60000}",
            fixedDelayString = "${turnos.recuperacion.intervalo-ms:60000}")
    public void recuperar() {
        recuperacion.recuperar();
    }
}
