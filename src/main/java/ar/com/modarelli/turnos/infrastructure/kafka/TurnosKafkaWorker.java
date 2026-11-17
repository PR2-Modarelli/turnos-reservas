package ar.com.modarelli.turnos.infrastructure.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(name = "turnos.kafka.enabled", havingValue = "true")
public class TurnosKafkaWorker {
    private static final Logger LOG = LoggerFactory.getLogger(TurnosKafkaWorker.class);
    private final ar.com.modarelli.turnos.domain.ports.in.FlujoReservasUseCase servicio;
    private final ar.com.modarelli.turnos.domain.ports.in.PublicarPendientesUseCase pendientes;
    private final ar.com.modarelli.turnos.domain.ports.out.FlujoPersistenciaPort datos;
    private final Clock reloj;

    public TurnosKafkaWorker(
            ar.com.modarelli.turnos.domain.ports.in.FlujoReservasUseCase servicio,
            ar.com.modarelli.turnos.domain.ports.in.PublicarPendientesUseCase pendientes,
            ar.com.modarelli.turnos.domain.ports.out.FlujoPersistenciaPort datos,
            Clock reloj,
            @Value("${turnos.kafka.phone-topic}") String topic,
            @Value("${turnos.kafka.actions-topic}") String acciones,
            @Value("${turnos.kafka.group-id}") String grupo,
            @Value("${turnos.kafka.consumer-group}") String consumidor,
            @Value("${turnos.kafka.bootstrap-servers}") String servidores) {
        if (topic.isBlank()
                || acciones.isBlank()
                || grupo.isBlank()
                || consumidor.isBlank()
                || servidores.isBlank())
            throw new IllegalArgumentException("Falta configurar Kafka de turnos");
        if (!topic.equals("catedra.turnos.telefono." + grupo)
                || !acciones.equals("alumnos.turnos.acciones." + grupo))
            throw new IllegalArgumentException("Topics incompatibles con el grupo del proyecto");
        this.servicio = servicio;
        this.pendientes = pendientes;
        this.datos = datos;
        this.reloj = reloj;
    }

    @KafkaListener(
            topics = "${turnos.kafka.actions-topic}",
            groupId = "${turnos.kafka.consumer-group}",
            containerFactory = "turnosKafkaFactory")
    public void recibir(ConsumerRecord<String, String> record) {
        try {
            servicio.recibir(record.key(), record.value());
        } catch (IllegalArgumentException e) {
            // Un mensaje incompatible se conserva para inspeccion, sin bloquear toda la particion
            // ni mostrar datos en logs.
            datos.cuarentena(
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.value(),
                    reloj.instant());
            LOG.warn(
                    "Evento de turnos incompatible conservado en cuarentena: particion={},"
                        + " offset={}",
                    record.partition(),
                    record.offset());
        }
    }

    @Scheduled(fixedDelayString = "${turnos.kafka.poll-ms:2000}")
    public void pendientes() {
        try {
            pendientes.publicarPendientes();
        } catch (RuntimeException e) {
            LOG.warn("Procesamiento de Kafka pendiente; se reintentara sin perder datos");
        }
    }
}
