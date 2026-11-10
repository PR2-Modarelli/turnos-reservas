package ar.com.modarelli.turnos.web;
import ar.com.modarelli.turnos.PostgresPruebas;
import ar.com.modarelli.turnos.auth.CuentaServicio;
import ar.com.modarelli.turnos.auth.CuentaDtos.*;
import ar.com.modarelli.turnos.kafka.*;
import ar.com.modarelli.turnos.reservas.ProcesoReservaRepository;
import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
@Import({PostgresPruebas.class,FlujoKafkaHttpTest.Tiempo.class})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.password=fixture","turnos.kafka.enabled=true","turnos.kafka.group-id=proyecto-fixture",
 "turnos.kafka.bootstrap-servers=127.0.0.1:1","turnos.kafka.consumer-group=turnos-fixture",
 "turnos.kafka.actions-topic=alumnos.turnos.acciones.proyecto-fixture","turnos.kafka.phone-topic=catedra.turnos.telefono.proyecto-fixture",
 "turnos.jwt-secret=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ=="})
class FlujoKafkaHttpTest {
 static final Instant AHORA=Instant.parse("2026-11-10T12:00:00Z");
 @TestConfiguration static class Tiempo {@Bean @Primary Clock pruebaClock(){return Clock.fixed(AHORA,ZoneOffset.UTC);}}
 @MockitoBean TurnosKafkaWorker worker;
 @Autowired JdbcTemplate jdbc;@Autowired CuentaServicio cuentas;@Autowired FlujoKafkaServicio flujo;
 @Autowired ProcesoReservaRepository procesos;@Autowired JwtDecoder decoder;
 @Value("${local.server.port}") int puerto;
 final JsonMapper json=JsonMapper.builder().build();final HttpClient http=HttpClient.newHttpClient();
 UUID id;String token,tokenOtro;Jwt jwt;long usuario;
 @BeforeEach void preparar(){
  jdbc.update("delete from evento_turnos_entrada");jdbc.update("delete from evento_turnos_descartado");jdbc.update("delete from proceso_reserva");jdbc.update("delete from app_user");
  usuario=cuentas.registrar(new Registro("kafka","clave-fixture","Usuario","Prueba","kafka@example.com",null,"es")).id();
  cuentas.registrar(new Registro("otro","clave-fixture","Otro","Prueba","otro@example.com",null,"es"));
  token=cuentas.autenticar(new Credenciales("kafka","clave-fixture",false)).id_token();jwt=decoder.decode(token);
  tokenOtro=cuentas.autenticar(new Credenciales("otro","clave-fixture",false)).id_token();id=UUID.randomUUID();
  jdbc.update("insert into proceso_reserva(id,usuario_id,idempotency_key,profesional_id,fecha,hora_inicio,estado,hold_id,reservation_process_id,vence_en,confirmacion_vence_en,creado_en,actualizado_en) values(?,?,?,202,'2026-11-12','18:00','CONFIRMANDO','hold-fixture','process-fixture',?,?,?,?)",
   id,usuario,UUID.randomUUID(),Timestamp.from(AHORA.plusSeconds(600)),Timestamp.from(AHORA.plusSeconds(600)),Timestamp.from(AHORA),Timestamp.from(AHORA));
 }
 Map<String,Object> evento(String tipo,String eid){
  var e=new LinkedHashMap<String,Object>();e.put("schemaVersion",1);e.put("eventId",eid);e.put("eventType",tipo);
  e.put("reservationProcessId","process-fixture");e.put("holdId","hold-fixture");e.put("occurredAt",AHORA.plusSeconds(1));
  if(tipo.equals("AdditionalInformationRequested")){e.put("requiredField","PHONE_NUMBER");e.put("expiresAt",AHORA.plusSeconds(600));}
  else{e.put("groupId","proyecto-fixture");e.put("requestEventId","request-fixture");e.put("reason","INVALID_PHONE_NUMBER");e.put("expiresAt",AHORA.plusSeconds(600));}
  if(tipo.equals("AppointmentConfirmed")){e.put("reservationId",8251);e.put("professionalId",202);e.put("date","2026-11-12");e.put("startTime","18:00");e.put("status","CONFIRMED");}
  return e;
 }
 void recibir(Map<String,Object> e){flujo.recibir("process-fixture",json.writeValueAsString(e));}
 void solicitar(){recibir(evento("AdditionalInformationRequested","request-fixture"));}
 String estado(){return jdbc.queryForObject("select estado from proceso_reserva where id=?",String.class,id);}
 int cantidad(String tabla){return jdbc.queryForObject("select count(*) from "+tabla,Integer.class);}
 HttpResponse<String> post(String t,UUID clave,String body)throws Exception {
  var req=HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+"/api/turnos/procesos/"+id+"/telefono")).header("Content-Type","application/json");
  if(t!=null)req.header("Authorization","Bearer "+t);if(clave!=null)req.header("Idempotency-Key",clave.toString());
  return http.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 HttpResponse<String> info(String t)throws Exception {
  return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+"/api/turnos/procesos/"+id+"/informacion")).header("Authorization","Bearer "+t).GET().build(),HttpResponse.BodyHandlers.ofString());
 }
 String telefono(){return "{\"telefono\":\"+54 (9261) 555-1234\"}";}
 @Test void solicitudAntesDeRespuestaRestNoSeSobrescribe(){solicitar();procesos.guardarConfirmacion(id,AHORA.plusSeconds(400),AHORA);assertThat(estado()).isEqualTo("ESPERANDO_TELEFONO");assertThat(flujo.informacion(id,jwt)).containsEntry("requestEventId","request-fixture");}
 @Test void solicitudDuplicadaNoProduceEfectos(){solicitar();solicitar();assertThat(cantidad("evento_turnos_entrada")).isEqualTo(1);}
 @Test void enviaTelefonoConIdentidadCorrelacionYEventoEstable()throws Exception {
  solicitar();UUID clave=UUID.randomUUID();assertThat(post(token,clave,telefono()).statusCode()).isEqualTo(202);
  assertThat(estado()).isEqualTo("TELEFONO_ENVIADO");var salida=jdbc.queryForMap("select * from telefono_salida");var body=json.readTree((String)salida.get("payload"));
  assertThat(body.get("eventType").asText()).isEqualTo("AdditionalInformationSubmitted");assertThat(body.get("groupId").asText()).isEqualTo("proyecto-fixture");
  assertThat(body.get("reservationProcessId").asText()).isEqualTo("process-fixture");assertThat(body.get("requestEventId").asText()).isEqualTo("request-fixture");
  assertThat(body.get("information").get("phoneNumber").asText()).isEqualTo("+5492615551234");
  assertThat(post(token,clave,telefono()).statusCode()).isEqualTo(202);assertThat(cantidad("telefono_salida")).isEqualTo(1);
 }
 @Test void claveNoSeReutilizaParaOtroTelefono()throws Exception{solicitar();var c=UUID.randomUUID();post(token,c,telefono());assertThat(post(token,c,"{\"telefono\":\"2614445555\"}").statusCode()).isEqualTo(409);}
 @Test void noEnviaSinSolicitud()throws Exception{assertThat(post(token,UUID.randomUUID(),telefono()).statusCode()).isEqualTo(409);assertThat(cantidad("telefono_salida")).isZero();}
 @Test void otroUsuarioNoConsultaNiEnvia()throws Exception{solicitar();assertThat(post(tokenOtro,UUID.randomUUID(),telefono()).statusCode()).isEqualTo(404);assertThat(info(tokenOtro).statusCode()).isEqualTo(404);}
 @Test void exigeAutenticacion()throws Exception{assertThat(post(null,UUID.randomUUID(),telefono()).statusCode()).isEqualTo(401);}
 @Test void rechazaPacienteElegidoPorCliente()throws Exception{solicitar();assertThat(post(token,UUID.randomUUID(),"{\"telefono\":\"2615551234\",\"usuarioId\":99}").statusCode()).isEqualTo(400);}
 @Test void telefonoInvalidoNoSePersiste()throws Exception{solicitar();assertThat(post(token,UUID.randomUUID(),"{\"telefono\":\"abc123\"}").statusCode()).isEqualTo(400);assertThat(cantidad("telefono_salida")).isZero();}
 @Test void exigeClave()throws Exception{solicitar();assertThat(post(token,null,telefono()).statusCode()).isEqualTo(400);}
 @Test void vencimientoImpideEnvio()throws Exception{solicitar();jdbc.update("update proceso_reserva set confirmacion_vence_en=? where id=?",Timestamp.from(AHORA.minusSeconds(1)),id);assertThat(post(token,UUID.randomUUID(),telefono()).statusCode()).isEqualTo(409);}
 @Test void confirmacionFinalSePersisteYNoRetrocede()throws Exception{solicitar();recibir(evento("AppointmentConfirmed","confirmed"));assertThat(estado()).isEqualTo("CONFIRMADO");recibir(evento("AdditionalInformationRejected","rechazo-tardio"));assertThat(estado()).isEqualTo("CONFIRMADO");assertThat(info(token).body()).contains("8251");}
 @Test void resultadoAntesDeSolicitudSeRecupera(){recibir(evento("AppointmentConfirmed","confirmed"));assertThat(estado()).isEqualTo("CONFIRMANDO");solicitar();flujo.reprocesar("confirmed");assertThat(estado()).isEqualTo("CONFIRMADO");}
 @Test void eventoAntesDeGuardarHoldSeRecupera(){jdbc.update("update proceso_reserva set hold_id=null,reservation_process_id=null where id=?",id);solicitar();assertThat(estado()).isEqualTo("CONFIRMANDO");jdbc.update("update proceso_reserva set hold_id='hold-fixture',reservation_process_id='process-fixture' where id=?",id);flujo.reprocesar("request-fixture");assertThat(estado()).isEqualTo("ESPERANDO_TELEFONO");}
 @Test void rechazoPermiteCorregirConNuevaClave()throws Exception{solicitar();post(token,UUID.randomUUID(),telefono());recibir(evento("AdditionalInformationRejected","rechazo"));assertThat(estado()).isEqualTo("ESPERANDO_TELEFONO");assertThat(post(token,UUID.randomUUID(),"{\"telefono\":\"2614445555\"}").statusCode()).isEqualTo(202);assertThat(cantidad("telefono_salida")).isEqualTo(2);recibir(evento("AdditionalInformationRejected","rechazo"));assertThat(estado()).isEqualTo("TELEFONO_ENVIADO");}
 @Test void noSeCreanEnviosParalelos()throws Exception{solicitar();try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->post(token,UUID.randomUUID(),telefono()).statusCode());var b=pool.submit(()->post(token,UUID.randomUUID(),telefono()).statusCode());assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(202,409);}assertThat(cantidad("telefono_salida")).isEqualTo(1);}
 @Test void datosDeReservaIncompatiblesNoSeAplican(){solicitar();var e=evento("AppointmentConfirmed","confirmed");e.put("professionalId",999);recibir(e);assertThat(estado()).isEqualTo("ESPERANDO_TELEFONO");}
 @Test void grupoOKeyIncorrectosNoSeAplican(){var e=evento("AdditionalInformationRequested","request");e.put("groupId","otro");assertThatThrownBy(()->recibir(e)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->flujo.recibir("otro",json.writeValueAsString(evento("AdditionalInformationRequested","r")))).isInstanceOf(IllegalArgumentException.class);assertThat(cantidad("evento_turnos_entrada")).isZero();}
 @Test void ignoraCamposNuevos(){var e=evento("AdditionalInformationRequested","request-fixture");e.put("campoFuturo",true);recibir(e);assertThat(estado()).isEqualTo("ESPERANDO_TELEFONO");}
 @Test void vencidoCentralCancelaSalida()throws Exception{solicitar();post(token,UUID.randomUUID(),telefono());recibir(evento("AppointmentProcessExpired","expired"));assertThat(estado()).isEqualTo("VENCIDO");assertThat(jdbc.queryForObject("select cancelado from telefono_salida",Boolean.class)).isTrue();}
 @Test void invalidoCentralEsTerminal(){solicitar();recibir(evento("AppointmentProcessInvalid","invalid"));assertThat(estado()).isEqualTo("PROCESO_INVALIDO");}
 @Test void solicitudDistintaNoReemplazaCorrelacion(){solicitar();recibir(evento("AdditionalInformationRequested","otra-solicitud"));assertThat(flujo.informacion(id,jwt)).containsEntry("requestEventId","request-fixture");}

 @SuppressWarnings("unchecked")
 org.springframework.kafka.core.KafkaTemplate<String,String> productor(){return org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);}
 TurnosKafkaWorker publicador(org.springframework.kafka.core.KafkaTemplate<String,String> productor){return new TurnosKafkaWorker(flujo,jdbc,productor,Clock.fixed(AHORA,ZoneOffset.UTC),"catedra.turnos.telefono.proyecto-fixture","alumnos.turnos.acciones.proyecto-fixture","proyecto-fixture","turnos-fixture","127.0.0.1:1");}
 @Test void falloDePublicacionConservaEventoYReintentaConMismoId() {
  solicitar();flujo.telefono(id,UUID.randomUUID(),"2615551234",jwt);var productor=productor();
  org.mockito.Mockito.when(productor.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
   .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker caido"))).thenReturn(CompletableFuture.completedFuture(null));
  var pub=publicador(productor);pub.pendientes();assertThat(jdbc.queryForObject("select publicado_en is null from telefono_salida",Boolean.class)).isTrue();
  pub.pendientes();assertThat(jdbc.queryForObject("select publicado_en is not null from telefono_salida",Boolean.class)).isTrue();
  var payload=org.mockito.ArgumentCaptor.forClass(String.class);
  org.mockito.Mockito.verify(productor,org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers.eq("catedra.turnos.telefono.proyecto-fixture"),org.mockito.ArgumentMatchers.eq("process-fixture"),payload.capture());
  assertThat(payload.getAllValues().get(0)).isEqualTo(payload.getAllValues().get(1));
 }
 @Test void ackSinGuardadoLocalRepiteMismoEvento() {
  solicitar();flujo.telefono(id,UUID.randomUUID(),"2615551234",jwt);var productor=productor();
  org.mockito.Mockito.when(productor.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(CompletableFuture.completedFuture(null));
  jdbc.execute("alter table telefono_salida add constraint fallo_ack_fixture check(publicado_en is null)");
  try {publicador(productor).pendientes();assertThat(jdbc.queryForObject("select publicado_en is null from telefono_salida",Boolean.class)).isTrue();}
  finally{jdbc.execute("alter table telefono_salida drop constraint fallo_ack_fixture");}
  publicador(productor).pendientes();var payload=org.mockito.ArgumentCaptor.forClass(String.class);
  org.mockito.Mockito.verify(productor,org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("process-fixture"),payload.capture());
  assertThat(payload.getAllValues().get(0)).isEqualTo(payload.getAllValues().get(1));
 }
 @Test void noPublicaTelefonoFueraDePlazo() {
  solicitar();flujo.telefono(id,UUID.randomUUID(),"2615551234",jwt);
  jdbc.update("update proceso_reserva set confirmacion_vence_en=? where id=?",Timestamp.from(AHORA.minusSeconds(1)),id);
  var productor=productor();publicador(productor).pendientes();org.mockito.Mockito.verifyNoInteractions(productor);
  assertThat(jdbc.queryForObject("select cancelado from telefono_salida",Boolean.class)).isTrue();
 }
}
