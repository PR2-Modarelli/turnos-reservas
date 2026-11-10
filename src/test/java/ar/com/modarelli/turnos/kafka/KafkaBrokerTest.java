package ar.com.modarelli.turnos.kafka;
import ar.com.modarelli.turnos.PostgresPruebas;
import ar.com.modarelli.turnos.auth.CuentaServicio;
import ar.com.modarelli.turnos.auth.CuentaDtos.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
@Import({PostgresPruebas.class,KafkaBrokerTest.Tiempo.class})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.password=fixture","turnos.kafka.enabled=true","turnos.kafka.group-id=broker-fixture",
 "turnos.kafka.consumer-group=turnos-broker-fixture","turnos.kafka.poll-ms=100",
 "turnos.kafka.actions-topic=alumnos.turnos.acciones.broker-fixture","turnos.kafka.phone-topic=catedra.turnos.telefono.broker-fixture",
 "turnos.jwt-secret=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ=="})
class KafkaBrokerTest {
 static final Instant AHORA=Instant.parse("2026-11-10T12:00:00Z");
 static final String ACCIONES="alumnos.turnos.acciones.broker-fixture",TELEFONO="catedra.turnos.telefono.broker-fixture";
 static final KafkaContainer BROKER=new KafkaContainer("apache/kafka-native:4.3.1");
 @TestConfiguration static class Tiempo {@Bean @Primary Clock clockFixture(){return Clock.fixed(AHORA,ZoneOffset.UTC);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry p)throws Exception {
  BROKER.start();p.add("turnos.kafka.bootstrap-servers",BROKER::getBootstrapServers);
  try(var admin=Admin.create(Map.of("bootstrap.servers",BROKER.getBootstrapServers()))){admin.createTopics(List.of(new NewTopic(ACCIONES,1,(short)1),new NewTopic(TELEFONO,1,(short)1))).all().get(20,TimeUnit.SECONDS);}
 }
 @Autowired JdbcTemplate jdbc;@Autowired CuentaServicio cuentas;@Autowired FlujoKafkaServicio flujo;@Autowired JwtDecoder decoder;@Autowired KafkaTemplate<String,String> kafka;
 final JsonMapper json=JsonMapper.builder().build();UUID id;Jwt jwt;String remoto,hold,request;
 @BeforeEach void preparar(){
  String sufijo=UUID.randomUUID().toString();remoto="process-"+sufijo;hold="hold-"+sufijo;request="request-"+sufijo;
  String login="u"+sufijo.substring(0,8);var u=cuentas.registrar(new Registro(login,"clave-fixture","Usuario","Prueba",login+"@example.com",null,"es"));
  jwt=decoder.decode(cuentas.autenticar(new Credenciales(login,"clave-fixture",false)).id_token());id=UUID.randomUUID();
  jdbc.update("insert into proceso_reserva(id,usuario_id,idempotency_key,profesional_id,fecha,hora_inicio,estado,hold_id,reservation_process_id,vence_en,confirmacion_vence_en,creado_en,actualizado_en) values(?,?,?,202,'2026-11-12','18:00','CONFIRMANDO',?,?,?,?,?,?)",
   id,u.id(),UUID.randomUUID(),hold,remoto,Timestamp.from(AHORA.plusSeconds(600)),Timestamp.from(AHORA.plusSeconds(600)),Timestamp.from(AHORA),Timestamp.from(AHORA));
 }
 Map<String,Object> solicitud(){return Map.of("eventId",request,"eventType","AdditionalInformationRequested","schemaVersion",1,"reservationProcessId",remoto,"holdId",hold,"requiredField","PHONE_NUMBER","occurredAt",AHORA,"expiresAt",AHORA.plusSeconds(600));}
 void enviar(String payload)throws Exception{kafka.send(ACCIONES,remoto,payload).get(10,TimeUnit.SECONDS);}
 String estado(){return jdbc.queryForObject("select estado from proceso_reserva where id=?",String.class,id);}
 void esperar(BooleanSupplier condicion)throws Exception{long fin=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!condicion.getAsBoolean() && System.nanoTime()<fin)Thread.sleep(50);assertThat(condicion.getAsBoolean()).isTrue();}
 @Test void recorridoConBrokerRealYClaveDeProceso()throws Exception {
  enviar(json.writeValueAsString(solicitud()));esperar(()->estado().equals("ESPERANDO_TELEFONO"));
  try(var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",BROKER.getBootstrapServers(),"group.id","verificacion-"+UUID.randomUUID(),"auto.offset.reset","earliest","key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class))){
   consumer.subscribe(List.of(TELEFONO));flujo.telefono(id,UUID.randomUUID(),"+54 9261 5551234",jwt);
   String[] payload={null};long fin=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
   while(payload[0]==null && System.nanoTime()<fin)for(var r:consumer.poll(Duration.ofMillis(200)))if(remoto.equals(r.key()))payload[0]=r.value();
   assertThat(payload[0]).isNotNull();var e=json.readTree(payload[0]);assertThat(e.get("requestEventId").asText()).isEqualTo(request);
   assertThat(e.get("information").get("phoneNumber").asText()).isEqualTo("+5492615551234");
   esperar(()->jdbc.queryForObject("select publicado_en is not null from telefono_salida where proceso_id=?",Boolean.class,id));
  }
  var confirmado=new LinkedHashMap<String,Object>();confirmado.put("eventId","confirmed-"+UUID.randomUUID());confirmado.put("eventType","AppointmentConfirmed");confirmado.put("schemaVersion",1);
  confirmado.put("groupId","broker-fixture");confirmado.put("reservationProcessId",remoto);confirmado.put("holdId",hold);confirmado.put("requestEventId",request);
  confirmado.put("reservationId",8251);confirmado.put("professionalId",202);confirmado.put("date","2026-11-12");confirmado.put("startTime","18:00");confirmado.put("status","CONFIRMED");confirmado.put("occurredAt",AHORA.plusSeconds(1));
  enviar(json.writeValueAsString(confirmado));esperar(()->estado().equals("CONFIRMADO"));enviar(json.writeValueAsString(confirmado));
  assertThat(flujo.informacion(id,jwt)).containsEntry("reservationId",8251L);
 }
 @Test void mensajeIlegibleSeConservaSinBloquearLaParticion()throws Exception {
  int antes=jdbc.queryForObject("select count(*) from evento_turnos_descartado",Integer.class);
  enviar("no-es-json");enviar(json.writeValueAsString(solicitud()));esperar(()->estado().equals("ESPERANDO_TELEFONO"));
  assertThat(jdbc.queryForObject("select count(*) from evento_turnos_descartado",Integer.class)).isEqualTo(antes+1);
 }
 @AfterAll static void cerrar(){BROKER.stop();}
}
