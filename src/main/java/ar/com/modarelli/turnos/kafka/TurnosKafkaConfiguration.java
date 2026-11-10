package ar.com.modarelli.turnos.kafka;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
@Configuration
@EnableKafka
@EnableScheduling
@ConditionalOnProperty(name="turnos.kafka.enabled",havingValue="true")
public class TurnosKafkaConfiguration {
 @Bean public ConcurrentKafkaListenerContainerFactory<String,String> turnosKafkaFactory(@Value("${turnos.kafka.bootstrap-servers}") String servidores) {
  var props=new HashMap<String,Object>();props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,servidores);
  props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
  props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
  props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,20);
  var f=new ConcurrentKafkaListenerContainerFactory<String,String>();f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
  f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
  f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2000,FixedBackOff.UNLIMITED_ATTEMPTS)));
  return f;
 }
 @Bean public ProducerFactory<String,String> turnosProducerFactory(@Value("${turnos.kafka.bootstrap-servers}") String servidores) {
  return new DefaultKafkaProducerFactory<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,servidores,
   ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class,ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class,
   ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true,ProducerConfig.ACKS_CONFIG,"all",ProducerConfig.MAX_BLOCK_MS_CONFIG,5000,
   ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,15000,ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,10000));
 }
 @Bean public KafkaTemplate<String,String> turnosKafkaTemplate(ProducerFactory<String,String> turnosProducerFactory){return new KafkaTemplate<>(turnosProducerFactory);}
}
