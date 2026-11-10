package ar.com.modarelli.turnos.kafka;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Component
@ConditionalOnProperty(name="turnos.kafka.enabled",havingValue="true")
public class TurnosKafkaWorker {
 private static final Logger LOG=LoggerFactory.getLogger(TurnosKafkaWorker.class);
 private final FlujoKafkaServicio servicio;private final JdbcTemplate jdbc;private final KafkaTemplate<String,String> kafka;private final String topic;private final Clock reloj;
 public TurnosKafkaWorker(FlujoKafkaServicio servicio,JdbcTemplate jdbc,KafkaTemplate<String,String> kafka,Clock reloj,
  @Value("${turnos.kafka.phone-topic}") String topic,@Value("${turnos.kafka.actions-topic}") String acciones,
  @Value("${turnos.kafka.group-id}") String grupo,@Value("${turnos.kafka.consumer-group}") String consumidor,
  @Value("${turnos.kafka.bootstrap-servers}") String servidores) {
  if(topic.isBlank() || acciones.isBlank() || grupo.isBlank() || consumidor.isBlank() || servidores.isBlank())throw new IllegalArgumentException("Falta configurar Kafka de turnos");
  if(!topic.equals("catedra.turnos.telefono."+grupo) || !acciones.equals("alumnos.turnos.acciones."+grupo))throw new IllegalArgumentException("Topics incompatibles con el grupo del proyecto");
  this.servicio=servicio;this.jdbc=jdbc;this.kafka=kafka;this.topic=topic;this.reloj=reloj;
 }
 @KafkaListener(topics="${turnos.kafka.actions-topic}",groupId="${turnos.kafka.consumer-group}",containerFactory="turnosKafkaFactory")
 public void recibir(ConsumerRecord<String,String> record) {
  try {servicio.recibir(record.key(),record.value());}
  catch(IllegalArgumentException e) {
   // Un mensaje incompatible se conserva para inspeccion, sin bloquear toda la particion ni mostrar datos en logs.
   jdbc.update("insert into evento_turnos_descartado(topic,particion,posicion,payload,recibido_en) values(?,?,?,?,?) on conflict do nothing",
    record.topic(),record.partition(),record.offset(),record.value(),Timestamp.from(reloj.instant()));
   LOG.warn("Evento de turnos incompatible conservado en cuarentena: particion={}, offset={}",record.partition(),record.offset());
  }
 }
 @Scheduled(fixedDelayString="${turnos.kafka.poll-ms:2000}")
 public void pendientes() {
  try {
   for(var e:jdbc.queryForList("select event_id from evento_turnos_entrada where not procesado order by coalesce(ultimo_intento,recibido_en),event_id limit 100"))servicio.reprocesar((String)e.get("event_id"));
   jdbc.update("update telefono_salida s set cancelado=true from proceso_reserva p where s.proceso_id=p.id and s.publicado_en is null and not s.cancelado and (p.estado in ('CONFIRMADO','VENCIDO','PROCESO_INVALIDO') or p.confirmacion_vence_en<=?)",Timestamp.from(reloj.instant()));
   for(var e:jdbc.queryForList("select s.*,p.reservation_process_id from telefono_salida s join proceso_reserva p on p.id=s.proceso_id where s.publicado_en is null and not s.cancelado order by s.creado_en limit 20")) {
    try {
     kafka.send(topic,(String)e.get("reservation_process_id"),(String)e.get("payload")).get(5,TimeUnit.SECONDS);
     jdbc.update("update telefono_salida set publicado_en=? where event_id=?",Timestamp.from(reloj.instant()),e.get("event_id"));
    }catch(InterruptedException ex){Thread.currentThread().interrupt();return;}
     catch(Exception ex){LOG.warn("Envio de telefono pendiente; se reintentara con el mismo eventId");return;}
   }
  }catch(RuntimeException ex){LOG.warn("Procesamiento de Kafka pendiente; se reintentara sin perder datos");}
 }
}
