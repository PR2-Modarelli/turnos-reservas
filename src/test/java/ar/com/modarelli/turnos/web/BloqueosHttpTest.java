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

@Import({PostgresPruebas.class,BloqueosHttpTest.Tiempo.class})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.password=fixture",
 "turnos.jwt-secret=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==",
 "turnos.catedra-token=tecnico-fixture"})
class BloqueosHttpTest {
    static final Instant AHORA=Instant.parse("2026-11-07T15:00:00Z");
    static final LocalDate FECHA=LocalDate.of(2026,11,10);
    static HttpServer remoto;
    static ExecutorService hilos;
    static final AtomicInteger envios=new AtomicInteger(),agendas=new AtomicInteger();
    static final AtomicReference<String> bodyEnviado=new AtomicReference<>(),authHold=new AtomicReference<>(),authAgenda=new AtomicReference<>();
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
    @Test void creaHoldConPropietarioJWTYContratoReal()throws Exception{
        var respuesta=crear(jwt,UUID.randomUUID(),solicitud());
        assertThat(respuesta.statusCode()).isEqualTo(201);
        var body=json.readTree(respuesta.body());assertThat(body.get("estado").asText()).isEqualTo("BLOQUEADO");
        assertThat(body.get("holdId").asText()).isEqualTo("hold-fixture");
        assertThat(Instant.parse(body.get("expiresAt").asText())).isEqualTo(AHORA.plusSeconds(300));
        assertThat(respuesta.headers().firstValue("Location")).contains("/api/turnos/procesos/"+id(respuesta));
        var enviado=json.readTree(bodyEnviado.get());assertThat(enviado.get("externalPatientId").asText()).isEqualTo(usuario.publicId().toString());
        assertThat(enviado.get("professionalId").asLong()).isEqualTo(15);
        assertThat(enviado.get("date").asText()).isEqualTo("2026-11-10");
        assertThat(authHold.get()).isEqualTo("Bearer tecnico-fixture");assertThat(authAgenda.get()).isEqualTo("Bearer "+jwt);
        assertThat(jdbc.queryForObject("select usuario_id from proceso_reserva",Long.class)).isEqualTo(usuario.id());
        assertThat(respuesta.body()).doesNotContain("tecnico-fixture","password","usuarioId");
    }
    @Test void exigeTokenYClaveAntesDeCrearIntento()throws Exception{
        assertThat(crear(null,UUID.randomUUID(),solicitud()).statusCode()).isEqualTo(401);
        assertThat(crear(jwt,null,solicitud()).statusCode()).isEqualTo(400);
        assertThat(envios.get()).isZero();assertThat(jdbc.queryForObject("select count(*) from proceso_reserva",Integer.class)).isZero();
    }
    @Test void noAdmitePropietarioEnBody()throws Exception{
        var body=solicitud().replace("}",",\"usuarioId\":"+otro.id()+"}");
        assertThat(crear(jwt,UUID.randomUUID(),body).statusCode()).isEqualTo(400);assertThat(envios.get()).isZero();
    }
    @Test void consultaSoloElPropietario()throws Exception{
        var r=crear(jwt,UUID.randomUUID(),solicitud());
        assertThat(consultar(jwt,id(r)).statusCode()).isEqualTo(200);
        assertThat(consultar(jwtOtro,id(r)).statusCode()).isEqualTo(404);
    }
    @Test void mismaClaveRepiteResultadoSinLlamarDependencias()throws Exception{
        UUID clave=UUID.randomUUID();var primera=crear(jwt,clave,solicitud());var segunda=crear(jwt,clave,solicitud());
        assertThat(segunda.statusCode()).isEqualTo(200);assertThat(id(segunda)).isEqualTo(id(primera));
        assertThat(envios.get()).isEqualTo(1);assertThat(agendas.get()).isEqualTo(1);
    }
    @Test void mismaClaveConOtroTurnoEsConflicto()throws Exception{
        UUID clave=UUID.randomUUID();crear(jwt,clave,solicitud());
        var otra=crear(jwt,clave,solicitud().replace("11:30","12:00"));
        assertThat(otra.statusCode()).isEqualTo(409);assertThat(otra.body()).contains("IDEMPOTENCY_KEY_REUTILIZADA");assertThat(envios.get()).isEqualTo(1);
    }
    @Test void otraClaveMismoUsuarioYSlotNoDuplicaHold()throws Exception{
        crear(jwt,UUID.randomUUID(),solicitud());var otra=crear(jwt,UUID.randomUUID(),solicitud());
        assertThat(otra.statusCode()).isEqualTo(409);assertThat(otra.body()).contains("PROCESO_ACTIVO_EXISTENTE");assertThat(envios.get()).isEqualTo(1);
    }
    @Test void concurrenciaConMismaClaveSoloEnviaUnaVez()throws Exception{
        UUID clave=UUID.randomUUID();demorar=true;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->crear(jwt,clave,solicitud()));assertThat(entro.await(10,TimeUnit.SECONDS)).isTrue();
            var segunda=crear(jwt,clave,solicitud());assertThat(segunda.statusCode()).isEqualTo(202);
            salir.countDown();assertThat(a.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        }finally{salir.countDown();}
        assertThat(envios.get()).isEqualTo(1);
    }
    @Test void procesoPersistidoAntesDeLlamarCatedra()throws Exception{
        demorar=true;
        try(var pool=Executors.newSingleThreadExecutor()) {
            var futuro=pool.submit(()->crear(jwt,UUID.randomUUID(),solicitud()));assertThat(entro.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select estado from proceso_reserva",String.class)).isEqualTo("SOLICITANDO");
            salir.countDown();assertThat(futuro.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        }finally{salir.countDown();}
    }
    @Test void slotFueraDeAgendaSeRechazaSinPostRemoto()throws Exception{
        var r=crear(jwt,UUID.randomUUID(),solicitud().replace("11:30","11:45"));
        assertThat(r.statusCode()).isEqualTo(400);assertThat(r.body()).contains("INVALID_SLOT");assertThat(envios.get()).isZero();
        assertThat(jdbc.queryForObject("select estado from proceso_reserva",String.class)).isEqualTo("RECHAZADO");
    }
    @Test void profesionalDeshabilitadoNoPideHold()throws Exception{
        respuestaAgenda=respuestaAgenda.replace("\"habilitado\":true","\"habilitado\":false");
        var r=crear(jwt,UUID.randomUUID(),solicitud());assertThat(r.statusCode()).isEqualTo(400);assertThat(r.body()).contains("PROFESSIONAL_DISABLED");assertThat(envios.get()).isZero();
    }
    @Test void diaSinAgendaNoPideHold()throws Exception{
        var r=crear(jwt,UUID.randomUUID(),solicitud().replace("2026-11-10","2026-11-09"));assertThat(r.statusCode()).isEqualTo(400);assertThat(envios.get()).isZero();
    }
    @Test void fechaPasadaNoConsultaDependencias()throws Exception{
        var r=crear(jwt,UUID.randomUUID(),solicitud().replace("2026-11-10","2026-11-06"));assertThat(r.statusCode()).isEqualTo(400);assertThat(envios.get()).isZero();assertThat(agendas.get()).isZero();
    }
    @Test void catalogoCaidoNoIniciaOperacionRemota()throws Exception{
        statusAgenda=503;respuestaAgenda="{}";var r=crear(jwt,UUID.randomUUID(),solicitud());
        assertThat(r.statusCode()).isEqualTo(503);assertThat(envios.get()).isZero();assertThat(r.body()).contains("procesoId");
    }
    @Test void conflictoRemotoQuedaPersistidoYNoSeRepite()throws Exception{
        statusHold=409;respuestaHold="{\"code\":\"SLOT_ALREADY_HELD\",\"detail\":\"dato privado\"}";
        UUID clave=UUID.randomUUID();var a=crear(jwt,clave,solicitud());var b=crear(jwt,clave,solicitud());
        assertThat(a.statusCode()).isEqualTo(409);assertThat(b.statusCode()).isEqualTo(409);
        assertThat(a.body()).contains("SLOT_ALREADY_HELD","procesoId").doesNotContain("dato privado");assertThat(envios.get()).isEqualTo(1);
    }
    @Test void otroUsuarioNoSeConfundePorLaMismaClave()throws Exception{
        UUID clave=UUID.randomUUID();crear(jwt,clave,solicitud());
        statusHold=409;respuestaHold="{\"code\":\"SLOT_ALREADY_HELD\"}";
        assertThat(crear(jwtOtro,clave,solicitud()).statusCode()).isEqualTo(409);
        assertThat(envios.get()).isEqualTo(2);assertThat(jdbc.queryForObject("select count(*) from proceso_reserva",Integer.class)).isEqualTo(2);
    }
    @Test void errorServidorNoSeTrataComoRechazoDefinitivo()throws Exception{
        statusHold=503;respuestaHold="{\"detail\":\"secreto externo\"}";UUID clave=UUID.randomUUID();
        var r=crear(jwt,clave,solicitud());assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).contains("RESULTADO_HOLD_DESCONOCIDO").doesNotContain("secreto externo");
        var repetida=crear(jwt,clave,solicitud());assertThat(repetida.statusCode()).isEqualTo(202);
        assertThat(json.readTree(repetida.body()).get("estado").asText()).isEqualTo("RESULTADO_DESCONOCIDO");assertThat(envios.get()).isEqualTo(1);
    }
    @Test void respuestaPerdidaNoReenviaPostNiConOtraClave()throws Exception{
        cortarRespuesta=true;UUID clave=UUID.randomUUID();var r=crear(jwt,clave,solicitud());assertThat(r.statusCode()).isEqualTo(503);
        assertThat(crear(jwt,clave,solicitud()).statusCode()).isEqualTo(202);
        assertThat(crear(jwt,UUID.randomUUID(),solicitud()).statusCode()).isEqualTo(409);assertThat(envios.get()).isEqualTo(1);
    }
    @Test void respuestaExitosaIncompletaNoSeInventaVencimiento()throws Exception{
        respuestaHold="{\"holdId\":\"hold-fixture\",\"status\":\"HELD\"}";
        var r=crear(jwt,UUID.randomUUID(),solicitud());assertThat(r.statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("select estado from proceso_reserva",String.class)).isEqualTo("RESULTADO_DESCONOCIDO");
        assertThat(jdbc.queryForObject("select vence_en from proceso_reserva",java.sql.Timestamp.class)).isNull();
    }
    @Test void vencimientoNoSeExtiendePorRepetirLaSolicitud()throws Exception{
        UUID clave=UUID.randomUUID();var r=crear(jwt,clave,solicitud());reloj.poner(AHORA.plusSeconds(301));
        var repetida=crear(jwt,clave,solicitud());assertThat(repetida.statusCode()).isEqualTo(200);
        assertThat(json.readTree(repetida.body()).get("estado").asText()).isEqualTo("VENCIDO");
        assertThat(id(repetida)).isEqualTo(id(r));assertThat(envios.get()).isEqualTo(1);
        assertThat(Instant.parse(json.readTree(repetida.body()).get("expiresAt").asText())).isEqualTo(AHORA.plusSeconds(300));
    }
    @Test void despuesDeVencerSePuedeIntentarOtraVezConOtraClave()throws Exception{
        crear(jwt,UUID.randomUUID(),solicitud());reloj.poner(AHORA.plusSeconds(301));
        respuestaHold=hold("hold-nuevo","process-nuevo",AHORA.plusSeconds(600));
        assertThat(crear(jwt,UUID.randomUUID(),solicitud()).statusCode()).isEqualTo(201);assertThat(envios.get()).isEqualTo(2);
    }
    @Test void estadoEnviadoPersistidoTrasInterrupcionNoSeReenvia()throws Exception{
        UUID clave=UUID.randomUUID();var s=new Solicitud(15L,FECHA,LocalTime.of(11,30));
        var p=procesos.iniciar(usuario.id(),clave,s,AHORA).proceso();procesos.marcarEnvio(p.id(),AHORA);
        reloj.poner(AHORA.plusSeconds(31));
        var r=crear(jwt,clave,solicitud());assertThat(r.statusCode()).isEqualTo(202);
        assertThat(json.readTree(r.body()).get("estado").asText()).isEqualTo("RESULTADO_DESCONOCIDO");assertThat(envios.get()).isZero();
    }
    @Test void interrupcionPreviaAlEnvioSeDistingueDelResultadoIncierto()throws Exception{
        UUID clave=UUID.randomUUID();var p=procesos.iniciar(usuario.id(),clave,new Solicitud(15L,FECHA,LocalTime.of(11,30)),AHORA).proceso();
        reloj.poner(AHORA.plusSeconds(31));var r=crear(jwt,clave,solicitud());
        assertThat(r.statusCode()).isEqualTo(503);assertThat(r.body()).contains("VALIDACION_INTERRUMPIDA");assertThat(envios.get()).isZero();
        assertThat(procesos.marcarEnvio(p.id(),reloj.instant())).isFalse();
    }
    @Test void usuarioInactivoNoCreaBloqueo()throws Exception{
        jdbc.update("update app_user set activated=false where id=?",usuario.id());
        assertThat(crear(jwt,UUID.randomUUID(),solicitud()).statusCode()).isEqualTo(401);assertThat(envios.get()).isZero();
    }
    @Test void timeoutDeLecturaConservaIntentoYNoRepitePost()throws Exception{
        demorar=true;UUID clave=UUID.randomUUID();
        try {
            var r=crear(jwt,clave,solicitud());assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).contains("RESULTADO_HOLD_DESCONOCIDO");
            assertThat(crear(jwt,clave,solicitud()).statusCode()).isEqualTo(202);
            assertThat(envios.get()).isEqualTo(1);
        }finally{salir.countDown();}
    }
    @Test void falloAlPersistirHoldNoBorraIntentoNiReenviaSolicitud()throws Exception{
        jdbc.execute("alter table proceso_reserva add constraint ck_test_fallo_hold check (hold_id is null)");
        UUID clave=UUID.randomUUID();
        try {
            var r=crear(jwt,clave,solicitud());assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).contains("RESULTADO_HOLD_DESCONOCIDO","procesoId");
            assertThat(jdbc.queryForObject("select estado from proceso_reserva",String.class)).isEqualTo("SOLICITANDO");
            assertThat(crear(jwt,clave,solicitud()).statusCode()).isEqualTo(202);
            reloj.poner(AHORA.plusSeconds(31));
            var consulta=crear(jwt,clave,solicitud());
            assertThat(json.readTree(consulta.body()).get("estado").asText()).isEqualTo("RESULTADO_DESCONOCIDO");
            assertThat(envios.get()).isEqualTo(1);
        }finally{jdbc.execute("alter table proceso_reserva drop constraint ck_test_fallo_hold");}
    }
    @Test void dosClavesConcurrentesParaMismoUsuarioSlotNoEnviaranDosHolds()throws Exception{
        demorar=true;
        try(var pool=Executors.newSingleThreadExecutor()){
            var primera=pool.submit(()->crear(jwt,UUID.randomUUID(),solicitud()));
            assertThat(entro.await(10,TimeUnit.SECONDS)).isTrue();
            var segunda=crear(jwt,UUID.randomUUID(),solicitud());
            assertThat(segunda.statusCode()).isEqualTo(409);assertThat(segunda.body()).contains("PROCESO_ACTIVO_EXISTENTE");
            salir.countDown();assertThat(primera.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        }finally{salir.countDown();}
        assertThat(envios.get()).isEqualTo(1);
    }
    @Test void claveUUIDMalformadaNoSeConfundeConFaltaDePermisos()throws Exception{
        var r=cliente.send(HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+"/api/turnos/bloqueos"))
            .header("Content-Type","application/json").header("Authorization","Bearer "+jwt).header("Idempotency-Key","no-es-uuid")
            .POST(HttpRequest.BodyPublishers.ofString(solicitud())).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(400);assertThat(r.body()).contains("VALIDATION_ERROR");assertThat(envios.get()).isZero();
    }
    @Test void tokenSinCuentaPersistidaNoPuedeCrearHold()throws Exception{
        String demo=new SeguridadHttpTest().token("ROLE_USER",AHORA.plusSeconds(3600).getEpochSecond(),"a".repeat(64));
        assertThat(crear(demo,UUID.randomUUID(),solicitud()).statusCode()).isEqualTo(401);assertThat(envios.get()).isZero();
    }
    @Test void horaFraccionariaSeRechazaAntesDePerderPrecisionEnPostgres()throws Exception{
        UUID clave=UUID.randomUUID();String body=solicitud().replace("11:30","11:30:00.0000001");
        assertThat(crear(jwt,clave,body).statusCode()).isEqualTo(400);
        assertThat(crear(jwt,clave,body).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from proceso_reserva",Integer.class)).isZero();assertThat(envios.get()).isZero();
    }
}
