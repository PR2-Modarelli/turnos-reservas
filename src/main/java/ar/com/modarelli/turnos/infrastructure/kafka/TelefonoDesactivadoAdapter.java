package ar.com.modarelli.turnos.infrastructure.kafka;

import ar.com.modarelli.turnos.domain.ports.out.PublicarTelefonoPort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "turnos.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class TelefonoDesactivadoAdapter implements PublicarTelefonoPort {
    public void publicar(String proceso, String contenido) {
        throw new IllegalStateException("Kafka desactivado");
    }
}
