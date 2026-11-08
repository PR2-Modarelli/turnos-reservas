package ar.com.modarelli.turnos.web;

import ar.com.modarelli.turnos.PostgresPruebas;
import ar.com.modarelli.turnos.auth.CuentaServicio;
import ar.com.modarelli.turnos.auth.CuentaDtos.*;
import ar.com.modarelli.turnos.reservas.*;
import ar.com.modarelli.turnos.reservas.BloqueoDtos.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Import({PostgresPruebas.class,ConfirmacionHttpTest.Tiempo.class})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.password=fixture",
 "turnos.jwt-secret=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==",
 "turnos.catedra-token=tecnico-fixture"})
class ConfirmacionHttpTest {
    static final Instant AHORA=Instant.parse("2026-11-07T15:00:00Z");
    static final LocalDate FECHA=LocalDate.of(2026,11,10);
    static HttpServer remoto;
    static ExecutorService hilos;
    static final AtomicInteger envios=new AtomicInteger(),agendas=new AtomicInteger();
    static final AtomicReference<String> bodyEnviado=new AtomicReference<>(),authHold=new AtomicReference<>(),authAgenda=new AtomicReference<>();
    static final AtomicInteger confirmaciones=new AtomicInteger();
    static final AtomicReference<String> bodyConfirmacion=new AtomicReference<>(),authConfirmacion=new AtomicReference<>();
    static volatile int statusConfirmacion;
    static volatile String respuestaConfirmacion;
    static volatile boolean cortarConfirmacion,demorarConfirmacion;
    static volatile CountDownLatch entroConfirmacion,salirConfirmacion;
    static volatile int statusHold,statusAgenda;
    static volatile String respuestaHold,respuestaAgenda;
    static volatile boolean cortarRespuesta,demorar;
    static volatile CountDownLatch entro,salir;
    @Value("${local.server.port}") int puerto;
    @Autowired CuentaServicio cuentas;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProcesoReservaRepository procesos;
    @Autowired Reloj reloj;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient cliente=HttpClient.newHttpClient();
    String jwt,jwtOtro;
    Cuenta usuario,otro;

    static class Reloj extends Clock {
        private volatile Instant valor=AHORA;
        public ZoneId getZone(){return ZoneId.of("America/Argentina/Mendoza");}
        public Clock withZone(ZoneId zona){return Clock.fixed(valor,zona);}
        public Instant instant(){return valor;}
        void poner(Instant instante){valor=instante;}
    }
    @TestConfiguration static class Tiempo { @Bean @Primary Reloj relojPrueba(){return new Reloj();} }
    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry props) throws Exception {
        remoto=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        hilos=Executors.newCachedThreadPool();remoto.setExecutor(hilos);
        remoto.createContext("/api/catalogo/profesionales/15/agenda",exchange->{
            agendas.incrementAndGet();authAgenda.set(exchange.getRequestHeaders().getFirst("Authorization"));
            responder(exchange,statusAgenda,respuestaAgenda);
        });
        remoto.createContext("/api/appointment-holds",exchange->{
            if(exchange.getRequestURI().getPath().endsWith("/confirm")) {
                confirmaciones.incrementAndGet();
                bodyConfirmacion.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                authConfirmacion.set(exchange.getRequestHeaders().getFirst("Authorization"));
                entroConfirmacion.countDown();
                if(demorarConfirmacion)try{salirConfirmacion.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                if(cortarConfirmacion){exchange.close();return;}
                responder(exchange,statusConfirmacion,respuestaConfirmacion);return;
            }
            envios.incrementAndGet();authHold.set(exchange.getRequestHeaders().getFirst("Authorization"));
            bodyEnviado.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            entro.countDown();
            if(demorar)try{salir.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(cortarRespuesta){exchange.close();return;}
            responder(exchange,statusHold,respuestaHold);
        });
        remoto.start();String url="http://127.0.0.1:"+remoto.getAddress().getPort();
        props.add("turnos.catalogo-url",()->url);props.add("turnos.catedra-url",()->url);
    }
    static void responder(HttpExchange e,int estado,String body) throws java.io.IOException {
        byte[] datos=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");
        e.sendResponseHeaders(estado,datos.length);try(var out=e.getResponseBody()){out.write(datos);}
    }
    @AfterAll static void cerrar(){remoto.stop(0);hilos.shutdownNow();}
    @BeforeEach void preparar() {
        confirmaciones.set(0);bodyConfirmacion.set(null);authConfirmacion.set(null);
        statusConfirmacion=202;cortarConfirmacion=false;demorarConfirmacion=false;
        entroConfirmacion=new CountDownLatch(1);salirConfirmacion=new CountDownLatch(1);
        respuestaConfirmacion="{\"reservationProcessId\":\"process-fixture\",\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\""+AHORA.plusSeconds(600)+"\",\"nuevo\":true}";
        reloj.poner(AHORA);jdbc.update("delete from proceso_reserva");jdbc.update("delete from app_user");
        envios.set(0);agendas.set(0);bodyEnviado.set(null);authHold.set(null);authAgenda.set(null);
        statusHold=201;statusAgenda=200;cortarRespuesta=false;demorar=false;
        entro=new CountDownLatch(1);salir=new CountDownLatch(1);
        respuestaAgenda="""
          {"version":58,"profesional":{"id":15,"nombre":"Ana","apellido":"Torres","habilitado":true},
           "categoriaHabilitada":true,"horarios":[{"id":1,"profesionalId":15,"diaSemana":"TUESDAY",
           "horaInicio":"11:30","horaFin":"14:30","duracionTurnoMinutos":30,"habilitado":true}]}
          """;
        respuestaHold=hold("hold-fixture","process-fixture",AHORA.plusSeconds(300));
        usuario=cuentas.registrar(new Registro("usuario","clave-fixture","Usuario","Prueba","usuario@example.com",null,"es"));
        otro=cuentas.registrar(new Registro("otro","clave-fixture","Otro","Prueba","otro@example.com",null,"es"));
        jwt=cuentas.autenticar(new Credenciales("usuario","clave-fixture",false)).id_token();
        jwtOtro=cuentas.autenticar(new Credenciales("otro","clave-fixture",false)).id_token();
    }
    String hold(String id,String proceso,Instant vence){return "{\"holdId\":\""+id+"\",\"reservationProcessId\":\""+proceso+"\",\"status\":\"HELD\",\"expiresAt\":\""+vence+"\",\"nuevo\":true}";}
    String solicitud(){return "{\"profesionalId\":15,\"fecha\":\"2026-11-10\",\"horaInicio\":\"11:30\"}";}
    HttpResponse<String> crear(String token,UUID clave,String body)throws Exception{
        var req=HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+"/api/turnos/bloqueos"))
            .header("Content-Type","application/json");
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(clave!=null)req.header("Idempotency-Key",clave.toString());
        return cliente.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> consultar(String token,String id)throws Exception{
        return cliente.send(HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+"/api/turnos/procesos/"+id))
            .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    String id(HttpResponse<String> respuesta){return json.readTree(respuesta.body()).get("id").asText();}
    String proceso()throws Exception{
        var r=crear(jwt,UUID.randomUUID(),solicitud());assertThat(r.statusCode()).isEqualTo(201);return id(r);
    }
    HttpResponse<String> confirmar(String token,String id,String body)throws Exception{
        var req=HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+"/api/turnos/procesos/"+id+"/confirmacion"));
        if(token!=null)req.header("Authorization","Bearer "+token);
        return cliente.send(req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void confirmaInicialmenteSinAfirmarReservaFinal()throws Exception{
        String id=proceso();var r=confirmar(jwt,id,"");
        assertThat(r.statusCode()).isEqualTo(202);
        var body=json.readTree(r.body());assertThat(body.get("estado").asText()).isEqualTo("ESPERANDO_TELEFONO");
        assertThat(Instant.parse(body.get("expiresAt").asText())).isEqualTo(AHORA.plusSeconds(300));
        assertThat(Instant.parse(body.get("confirmacionExpiresAt").asText())).isEqualTo(AHORA.plusSeconds(600));
        assertThat(body.get("reservationProcessId").asText()).isEqualTo("process-fixture");
        var envio=json.readTree(bodyConfirmacion.get());
        assertThat(envio.get("externalPatientId").asText()).isEqualTo(usuario.publicId().toString());
        assertThat(envio.get("patientFirstName").asText()).isEqualTo("Usuario");
        assertThat(envio.get("patientLastName").asText()).isEqualTo("Prueba");
        assertThat(authConfirmacion.get()).isEqualTo("Bearer tecnico-fixture");
        assertThat(r.body()).doesNotContain("tecnico-fixture","CONFIRMADO");
        assertThat(confirmaciones.get()).isEqualTo(1);
    }
    @Test void repeticionNoConfirmaDosVecesNiConsultaDeNuevoCatalogo()throws Exception{
        String id=proceso();confirmar(jwt,id,"");int consultas=agendas.get();
        var r=confirmar(jwt,id,"");assertThat(r.statusCode()).isEqualTo(202);
        assertThat(json.readTree(r.body()).get("estado").asText()).isEqualTo("ESPERANDO_TELEFONO");
        assertThat(confirmaciones.get()).isEqualTo(1);assertThat(agendas.get()).isEqualTo(consultas);
    }
    @Test void propietarioAjenoNoPuedeConfirmar()throws Exception{
        String id=proceso();assertThat(confirmar(jwtOtro,id,"").statusCode()).isEqualTo(404);
        assertThat(confirmaciones.get()).isZero();
    }
    @Test void sinTokenNoConfirma()throws Exception{
        assertThat(confirmar(null,proceso(),"").statusCode()).isEqualTo(401);assertThat(confirmaciones.get()).isZero();
    }
    @Test void noAceptaIdentidadElegidaPorCliente()throws Exception{
        var r=confirmar(jwt,proceso(),"{\"externalPatientId\":\"otro\",\"patientFirstName\":\"Otro\"}");
        assertThat(r.statusCode()).isEqualTo(400);assertThat(r.body()).contains("VALIDATION_ERROR");assertThat(confirmaciones.get()).isZero();
    }
    @Test void vencimientoLocalImpideEnviarConfirmacion()throws Exception{
        String id=proceso();reloj.poner(AHORA.plusSeconds(301));var r=confirmar(jwt,id,"");
        assertThat(r.statusCode()).isEqualTo(409);assertThat(r.body()).contains("HOLD_EXPIRED");assertThat(confirmaciones.get()).isZero();
        assertThat(jdbc.queryForObject("select estado from proceso_reserva",String.class)).isEqualTo("VENCIDO");
    }
    @Test void procesoSinHoldConocidoNoSeConfirma()throws Exception{
        String id=proceso();jdbc.update("update proceso_reserva set estado='RESULTADO_DESCONOCIDO' where id=?",UUID.fromString(id));
        var r=confirmar(jwt,id,"");assertThat(r.statusCode()).isEqualTo(409);assertThat(r.body()).contains("ESTADO_NO_CONFIRMABLE");assertThat(confirmaciones.get()).isZero();
    }
    @Test void cuentaSinNombreValidoNoEnviaDatosInventados()throws Exception{
        String id=proceso();jdbc.update("update app_user set first_name=null where id=?",usuario.id());
        var r=confirmar(jwt,id,"");assertThat(r.statusCode()).isEqualTo(400);assertThat(r.body()).contains("DATOS_PACIENTE_INCOMPLETOS");
        assertThat(confirmaciones.get()).isZero();assertThat(jdbc.queryForObject("select estado from proceso_reserva",String.class)).isEqualTo("BLOQUEADO");
    }
    @Test void catalogoCaidoAntesDelEnvioConservaHoldParaReintentar()throws Exception{
        String id=proceso();statusAgenda=503;var r=confirmar(jwt,id,"");assertThat(r.statusCode()).isEqualTo(503);
        assertThat(confirmaciones.get()).isZero();assertThat(jdbc.queryForObject("select estado from proceso_reserva",String.class)).isEqualTo("BLOQUEADO");
        statusAgenda=200;assertThat(confirmar(jwt,id,"").statusCode()).isEqualTo(202);assertThat(confirmaciones.get()).isEqualTo(1);
    }
    @Test void cambioDeAgendaImpideConfirmacion()throws Exception{
        String id=proceso();respuestaAgenda=respuestaAgenda.replace("\"habilitado\":true","\"habilitado\":false");
        assertThat(confirmar(jwt,id,"").statusCode()).isEqualTo(400);assertThat(confirmaciones.get()).isZero();
    }
    @Test void confirmacionesConcurrentesEnviaranUnaSolaVez()throws Exception{
        String id=proceso();demorarConfirmacion=true;
        try(var pool=Executors.newSingleThreadExecutor()){
            var primera=pool.submit(()->confirmar(jwt,id,""));assertThat(entroConfirmacion.await(10,TimeUnit.SECONDS)).isTrue();
            var segunda=confirmar(jwt,id,"");assertThat(segunda.statusCode()).isEqualTo(202);
            assertThat(json.readTree(segunda.body()).get("estado").asText()).isEqualTo("CONFIRMANDO");
            salirConfirmacion.countDown();assertThat(primera.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(202);
        }finally{salirConfirmacion.countDown();}
        assertThat(confirmaciones.get()).isEqualTo(1);
    }
    @Test void respuestaPerdidaNoPermiteReenvioCiego()throws Exception{
        String id=proceso();cortarConfirmacion=true;
        var r=confirmar(jwt,id,"");assertThat(r.statusCode()).isEqualTo(503);assertThat(r.body()).contains("RESULTADO_CONFIRMACION_DESCONOCIDO","procesoId");
        var repetida=confirmar(jwt,id,"");assertThat(repetida.statusCode()).isEqualTo(202);
        assertThat(json.readTree(repetida.body()).get("estado").asText()).isEqualTo("CONFIRMACION_DESCONOCIDA");assertThat(confirmaciones.get()).isEqualTo(1);
    }
    @Test void respuestaDeOtroProcesoNoSeAsociaAlPropio()throws Exception{
        String id=proceso();respuestaConfirmacion=respuestaConfirmacion.replace("process-fixture","otro-proceso");
        assertThat(confirmar(jwt,id,"").statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("select reservation_process_id from proceso_reserva",String.class)).isEqualTo("process-fixture");
        assertThat(jdbc.queryForObject("select estado from proceso_reserva",String.class)).isEqualTo("CONFIRMACION_DESCONOCIDA");
    }
    @Test void respuestaIncompletaNoSePresentaComoAceptada()throws Exception{
        String id=proceso();respuestaConfirmacion="{\"reservationProcessId\":\"process-fixture\",\"status\":\"WAITING_FOR_PHONE\"}";
        assertThat(confirmar(jwt,id,"").statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("select confirmacion_vence_en from proceso_reserva",java.sql.Timestamp.class)).isNull();
    }
    @Test void confirmacionPreviaRemotaRequiereVerificacionNoSeAsumeExitosa()throws Exception{
        String id=proceso();statusConfirmacion=409;respuestaConfirmacion="{\"code\":\"RESERVATION_PROCESS_ALREADY_CONFIRMED\"}";
        var r=confirmar(jwt,id,"");assertThat(r.statusCode()).isEqualTo(503);assertThat(r.body()).contains("CONFIRMACION_PREVIA_NO_VERIFICADA");
        assertThat(confirmar(jwt,id,"").statusCode()).isEqualTo(202);assertThat(confirmaciones.get()).isEqualTo(1);
    }
    @Test void rechazoContractualQuedaPersistidoYNoSeReenvia()throws Exception{
        String id=proceso();statusConfirmacion=404;respuestaConfirmacion="{\"code\":\"HOLD_EXPIRED\",\"detail\":\"secreto externo\"}";
        var r=confirmar(jwt,id,"");assertThat(r.statusCode()).isEqualTo(404);assertThat(r.body()).contains("HOLD_EXPIRED").doesNotContain("secreto externo");
        assertThat(confirmar(jwt,id,"").statusCode()).isEqualTo(404);assertThat(confirmaciones.get()).isEqualTo(1);
    }
    @Test void errorDeCredencialesTecnicasNoCierraSesionPropia()throws Exception{
        String id=proceso();statusConfirmacion=401;respuestaConfirmacion="{}";
        var r=confirmar(jwt,id,"");assertThat(r.statusCode()).isEqualTo(503);assertThat(r.body()).contains("INTEGRACION_NO_AUTORIZADA");
        assertThat(consultar(jwt,id).statusCode()).isEqualTo(200);
    }
    @Test void esperaDeTelefonoNoSeVenceComoSiFueraHoldSinConfirmar()throws Exception{
        String id=proceso();confirmar(jwt,id,"");reloj.poner(AHORA.plusSeconds(301));
        var r=consultar(jwt,id);assertThat(json.readTree(r.body()).get("estado").asText()).isEqualTo("ESPERANDO_TELEFONO");
        assertThat(crear(jwt,UUID.randomUUID(),solicitud()).statusCode()).isEqualTo(409);assertThat(envios.get()).isEqualTo(1);
    }
    @Test void intentoConfirmacionInterrumpidoSeConservaTrasConsulta()throws Exception{
        String id=proceso();procesos.prepararConfirmacion(UUID.fromString(id),usuario.id(),AHORA);
        reloj.poner(AHORA.plusSeconds(31));var r=confirmar(jwt,id,"");
        assertThat(r.statusCode()).isEqualTo(202);assertThat(json.readTree(r.body()).get("estado").asText()).isEqualTo("CONFIRMACION_DESCONOCIDA");
        assertThat(confirmaciones.get()).isZero();
    }
    @Test void falloDePersistenciaPosteriorAl202NoReenviaPost()throws Exception{
        String id=proceso();jdbc.execute("alter table proceso_reserva add constraint ck_test_confirmacion check (confirmacion_vence_en is null)");
        try{
            var r=confirmar(jwt,id,"");assertThat(r.statusCode()).isEqualTo(503);assertThat(r.body()).contains("RESULTADO_CONFIRMACION_DESCONOCIDO");
            assertThat(jdbc.queryForObject("select estado from proceso_reserva",String.class)).isEqualTo("CONFIRMANDO");
            assertThat(confirmar(jwt,id,"").statusCode()).isEqualTo(202);assertThat(confirmaciones.get()).isEqualTo(1);
        }finally{jdbc.execute("alter table proceso_reserva drop constraint ck_test_confirmacion");}
    }
    @Test void respuestaTardiaDelIntentoOriginalPuedeResolverIncertidumbre()throws Exception{
        String id=proceso();demorarConfirmacion=true;
        try(var pool=Executors.newSingleThreadExecutor()){
            var llamada=pool.submit(()->confirmar(jwt,id,""));assertThat(entroConfirmacion.await(10,TimeUnit.SECONDS)).isTrue();
            reloj.poner(AHORA.plusSeconds(31));
            assertThat(json.readTree(consultar(jwt,id).body()).get("estado").asText()).isEqualTo("CONFIRMACION_DESCONOCIDA");
            salirConfirmacion.countDown();assertThat(llamada.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(202);
            assertThat(json.readTree(consultar(jwt,id).body()).get("estado").asText()).isEqualTo("ESPERANDO_TELEFONO");
        }finally{salirConfirmacion.countDown();}
        assertThat(confirmaciones.get()).isEqualTo(1);
    }
    @Test void timeoutRealDeLecturaNoSeReintenta()throws Exception{
        String id=proceso();demorarConfirmacion=true;
        try{
            assertThat(confirmar(jwt,id,"").statusCode()).isEqualTo(503);
            assertThat(confirmar(jwt,id,"").statusCode()).isEqualTo(202);assertThat(confirmaciones.get()).isEqualTo(1);
        }finally{salirConfirmacion.countDown();}
    }
}
