package ar.com.modarelli.turnos.infrastructure.kafka;

import ar.com.modarelli.turnos.domain.ports.out.PublicarTelefonoPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name = "turnos.kafka.enabled", havingValue = "true")
public class KafkaTelefonoAdapter implements PublicarTelefonoPort {
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaTelefonoAdapter(
            KafkaTemplate<String, String> kafka,
            @Value("${turnos.kafka.phone-topic}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    public void publicar(String proceso, String contenido) throws InterruptedException {
        try {
            kafka.send(topic, proceso, contenido).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Envio de telefono pendiente", e);
        }
    }
}
