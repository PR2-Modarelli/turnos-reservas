package ar.com.modarelli.turnos.web;

import static org.assertj.core.api.Assertions.*;

import ar.com.modarelli.turnos.PostgresPruebas;
import ar.com.modarelli.turnos.application.usecases.CuentaServicio;
import ar.com.modarelli.turnos.application.usecases.FlujoKafkaServicio;
import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;

import com.sun.net.httpserver.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Import({PostgresPruebas.class, ReservasHttpTest.Tiempo.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.password=fixture",
            "turnos.catedra-token=tecnico-fixture",
            "turnos.kafka.group-id=proyecto-fixture",
            "turnos.jwt-secret=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ=="
        })
class ReservasHttpTest {
    static final Instant AHORA = Instant.parse("2026-11-10T21:00:00Z");

    @TestConfiguration
    static class Tiempo {
        @Bean
        @Primary
        Clock clockPrueba() {
            return Clock.fixed(AHORA, ZoneOffset.UTC);
        }
    }

    static HttpServer remoto;
    static ExecutorService hilos;
    static final AtomicInteger listados = new AtomicInteger(), cancelaciones = new AtomicInteger();
    static final AtomicReference<String> query = new AtomicReference<>(),
            autorizacion = new AtomicReference<>(),
            bodyCancelacion = new AtomicReference<>(),
            rutaCancelacion = new AtomicReference<>();
    static volatile String lista, total, respuestaCancelacion, segundaPagina;
    static volatile int statusLista, statusCancelacion;
    static volatile boolean cortar, demorar;
    static volatile CountDownLatch entro, salir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry p) throws Exception {
        remoto = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hilos = Executors.newCachedThreadPool();
        remoto.setExecutor(hilos);
        remoto.createContext(
                "/api/appointments",
                e -> {
                    autorizacion.set(e.getRequestHeaders().getFirst("Authorization"));
                    if (e.getRequestMethod().equals("GET")) {
                        listados.incrementAndGet();
                        query.set(e.getRequestURI().getQuery());
                        if (total != null) e.getResponseHeaders().set("X-Total-Count", total);
                        responder(
                                e,
                                statusLista,
                                segundaPagina != null
                                                && e.getRequestURI().getQuery().contains("page=1&")
                                        ? segundaPagina
                                        : lista);
                    } else {
                        cancelaciones.incrementAndGet();
                        rutaCancelacion.set(e.getRequestURI().getPath());
                        bodyCancelacion.set(
                                new String(
                                        e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        entro.countDown();
                        if (demorar)
                            try {
                                salir.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        if (cortar) {
                            e.close();
                            return;
                        }
                        responder(e, statusCancelacion, respuestaCancelacion);
                    }
                });
        remoto.start();
        p.add("turnos.catedra-url", () -> "http://127.0.0.1:" + remoto.getAddress().getPort());
    }

    static void responder(HttpExchange e, int status, String body) throws java.io.IOException {
        var b = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, b.length);
        try (var out = e.getResponseBody()) {
            out.write(b);
        }
    }

    @AfterAll
    static void cerrar() {
        remoto.stop(0);
        hilos.shutdownNow();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CuentaServicio cuentas;
    @Autowired FlujoKafkaServicio kafka;

    @Value("${local.server.port}")
    int puerto;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    String token, otroToken, paciente;
    UUID id;

    @BeforeEach
    void preparar() {
        jdbc.update("delete from evento_turnos_entrada");
        jdbc.update("delete from proceso_reserva");
        jdbc.update("delete from app_user");
        var u =
                cuentas.registrar(
                        new Registro(
                                "reservas",
                                "clave-fixture",
                                "Usuario",
                                "Prueba",
                                "reservas@example.com",
                                null,
                                "es"));
        paciente = u.publicId().toString();
        cuentas.registrar(
                new Registro(
                        "otro", "clave-fixture", "Otro", "Prueba", "otro@example.com", null, "es"));
        token = cuentas.autenticar(new Credenciales("reservas", "clave-fixture", false)).id_token();
        otroToken = cuentas.autenticar(new Credenciales("otro", "clave-fixture", false)).id_token();
        id = UUID.randomUUID();
        jdbc.update(
                "insert into"
                    + " proceso_reserva(id,usuario_id,idempotency_key,profesional_id,fecha,hora_inicio,estado,hold_id,reservation_process_id,reserva_id,solicitud_telefono_id,vence_en,creado_en,actualizado_en)"
                    + " values(?,?,?,202,'2026-11-12','18:00','CONFIRMADO','hold-fixture','process-fixture',5851,'request-fixture',?,?,?)",
                id,
                u.id(),
                UUID.randomUUID(),
                Timestamp.from(AHORA.plusSeconds(600)),
                Timestamp.from(AHORA),
                Timestamp.from(AHORA));
        listados.set(0);
        cancelaciones.set(0);
        autorizacion.set(null);
        query.set(null);
        statusLista = 200;
        statusCancelacion = 200;
        cortar = false;
        demorar = false;
        entro = new CountDownLatch(1);
        salir = new CountDownLatch(1);
        segundaPagina = null;
        lista = json.writeValueAsString(List.of(reserva("CONFIRMED")));
        total = "1";
        respuestaCancelacion =
                "{\"reservationId\":5851,\"reservationProcessId\":\"process-fixture\",\"status\":\"CANCELLED\",\"cancelledAt\":\"2026-11-10T21:00:00Z\",\"nuevo\":true}";
    }

    Map<String, Object> reserva(String estado) {
        var m = new LinkedHashMap<String, Object>();
        m.put("reservationId", 5851);
        m.put("reservationProcessId", "process-fixture");
        m.put("externalPatientId", paciente);
        m.put("appointmentDate", "2026-11-12");
        m.put("startTime", "18:00");
        m.put("endTime", "18:30");
        m.put("professionalId", 202);
        m.put("professionalFirstName", "Martin");
        m.put("professionalLastName", "Rivas");
        m.put("status", estado);
        m.put("createdAt", AHORA);
        if (estado.equals("CANCELLED")) {
            m.put("cancelledAt", AHORA);
            m.put("cancellationReason", "Motivo fixture");
        }
        return m;
    }

    HttpResponse<String> get(String t, String path) throws Exception {
        var r =
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + puerto + "/api/turnos/reservas" + path));
        if (t != null) r.header("Authorization", "Bearer " + t);
        return http.send(r.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> cancelar(String t, String body) throws Exception {
        var r =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + puerto
                                                + "/api/turnos/reservas/process-fixture/cancelacion"))
                        .header("Content-Type", "application/json");
        if (t != null) r.header("Authorization", "Bearer " + t);
        return http.send(
                r.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    String estado() {
        return jdbc.queryForObject(
                "select estado from proceso_reserva where id=?", String.class, id);
    }

    @Test
    void listaSoloPacienteAutenticadoYTokenTecnico() throws Exception {
        var r =
                get(
                        token,
                        "?pagina=0&tamanio=20&estado=CONFIRMED&desde=2026-11-01&hasta=2026-11-30");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("5851");
        assertThat(query.get())
                .contains(
                        "externalPatientId=" + paciente,
                        "status=CONFIRMED",
                        "from=2026-11-01",
                        "to=2026-11-30");
        assertThat(autorizacion.get()).isEqualTo("Bearer tecnico-fixture");
    }

    @Test
    void filtroDePacienteExternoNoPermiteElegirOtro() throws Exception {
        get(token, "?externalPatientId=otro");
        assertThat(query.get())
                .contains("externalPatientId=" + paciente)
                .doesNotContain("externalPatientId=otro");
    }

    @Test
    void respuestaConReservaAjenaFallaCerrada() throws Exception {
        var m = reserva("CONFIRMED");
        m.put("externalPatientId", "paciente-ajeno");
        lista = json.writeValueAsString(List.of(m));
        var r = get(token, "");
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.body()).doesNotContain("paciente-ajeno");
    }

    @Test
    void exigeAutenticacion() throws Exception {
        assertThat(get(null, "").statusCode()).isEqualTo(401);
        assertThat(cancelar(null, "").statusCode()).isEqualTo(401);
    }

    @Test
    void otroUsuarioNoConsultaDetalleNiCancela() throws Exception {
        assertThat(get(otroToken, "/process-fixture").statusCode()).isEqualTo(404);
        assertThat(cancelar(otroToken, "").statusCode()).isEqualTo(404);
        assertThat(listados.get()).isZero();
        assertThat(cancelaciones.get()).isZero();
    }

    @Test
    void detalleConsultaFuenteVigente() throws Exception {
        assertThat(get(token, "/process-fixture").statusCode()).isEqualTo(200);
        lista = json.writeValueAsString(List.of(reserva("CANCELLED")));
        assertThat(get(token, "/process-fixture").body()).contains("CANCELLED");
        assertThat(estado()).isEqualTo("CANCELADO");
    }

    @Test
    void cancelaYPersisteResultado() throws Exception {
        var r = cancelar(token, "{\"motivo\":\"Cambio de horario\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("CANCELLED");
        assertThat(estado()).isEqualTo("CANCELADO");
        assertThat(rutaCancelacion.get()).isEqualTo("/api/appointments/process-fixture/cancel");
        assertThat(bodyCancelacion.get()).contains("Cambio de horario");
        assertThat(autorizacion.get()).isEqualTo("Bearer tecnico-fixture");
    }

    @Test
    void motivoEsOpcional() throws Exception {
        assertThat(cancelar(token, "").statusCode()).isEqualTo(200);
    }

    @Test
    void rechazaMotivoLargoAntesDeLlamarCatedra() throws Exception {
        assertThat(
                        cancelar(token, json.writeValueAsString(Map.of("motivo", "x".repeat(501))))
                                .statusCode())
                .isEqualTo(400);
        assertThat(listados.get()).isZero();
    }

    @Test
    void noAceptaIdentidadDelCliente() throws Exception {
        assertThat(cancelar(token, "{\"motivo\":\"x\",\"usuarioId\":99}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void cancelacionRepetidaNoReenvia() throws Exception {
        cancelar(token, "");
        lista = json.writeValueAsString(List.of(reserva("CANCELLED")));
        assertThat(cancelar(token, "").statusCode()).isEqualTo(200);
        assertThat(cancelaciones.get()).isEqualTo(1);
    }

    @Test
    void consultaAntiguaNoResucitaReservaCancelada() throws Exception {
        cancelar(token, "");
        assertThat(cancelar(token, "").statusCode()).isEqualTo(200);
        assertThat(estado()).isEqualTo("CANCELADO");
        assertThat(cancelaciones.get()).isEqualTo(1);
    }

    @Test
    void noCancelaPendienteDeTelefono() throws Exception {
        lista = json.writeValueAsString(List.of(reserva("PHONE_PENDING")));
        assertThat(cancelar(token, "").statusCode()).isEqualTo(409);
        assertThat(cancelaciones.get()).isZero();
    }

    @Test
    void falloDeLecturaNoEnviaCancelacion() throws Exception {
        statusLista = 503;
        lista = "{}";
        assertThat(cancelar(token, "").statusCode()).isEqualTo(503);
        assertThat(cancelaciones.get()).isZero();
        assertThat(estado()).isEqualTo("CONFIRMADO");
    }

    @Test
    void respuestaPerdidaQuedaDesconocidaYSeReconcilia() throws Exception {
        cortar = true;
        assertThat(cancelar(token, "").statusCode()).isEqualTo(503);
        assertThat(estado()).isEqualTo("CANCELACION_DESCONOCIDA");
        cortar = false;
        lista = json.writeValueAsString(List.of(reserva("CANCELLED")));
        assertThat(cancelar(token, "").statusCode()).isEqualTo(200);
        assertThat(cancelaciones.get()).isEqualTo(1);
        assertThat(estado()).isEqualTo("CANCELADO");
    }

    @Test
    void cincoXXNoSeConsideraCancelacion() throws Exception {
        statusCancelacion = 503;
        respuestaCancelacion = "{}";
        assertThat(cancelar(token, "").statusCode()).isEqualTo(503);
        assertThat(estado()).isEqualTo("CANCELACION_DESCONOCIDA");
    }

    @Test
    void respuestaIncompatibleNoSeAcepta() throws Exception {
        respuestaCancelacion = respuestaCancelacion.replace("5851", "9999");
        assertThat(cancelar(token, "").statusCode()).isEqualTo(503);
        assertThat(estado()).isEqualTo("CANCELACION_DESCONOCIDA");
    }

    @Test
    void credencialTecnicaFallidaNoCierraSesionUsuario() throws Exception {
        statusCancelacion = 401;
        respuestaCancelacion = "{}";
        var r = cancelar(token, "");
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).contains("INTEGRACION_NO_AUTORIZADA");
    }

    @Test
    void rechazoCentralSeConserva() throws Exception {
        statusCancelacion = 409;
        respuestaCancelacion = "{\"code\":\"RESERVATION_NOT_CANCELLABLE\"}";
        assertThat(cancelar(token, "").statusCode()).isEqualTo(409);
        assertThat(estado()).isEqualTo("CONFIRMADO");
        assertThat(
                        jdbc.queryForObject(
                                "select error_code from proceso_reserva where id=?",
                                String.class,
                                id))
                .isEqualTo("RESERVATION_NOT_CANCELLABLE");
    }

    @Test
    void concurrenciaProduceUnSoloEnvio() throws Exception {
        demorar = true;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> cancelar(token, "").statusCode());
            assertThat(entro.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelar(token, "").statusCode()).isEqualTo(202);
            salir.countDown();
            assertThat(a.get()).isEqualTo(200);
        } finally {
            salir.countDown();
        }
        assertThat(cancelaciones.get()).isEqualTo(1);
    }

    @Test
    void validaFiltrosYLimites() throws Exception {
        for (String q :
                List.of(
                        "?pagina=-1",
                        "?tamanio=101",
                        "?estado=OTRO",
                        "?desde=2026-11-12&hasta=2026-11-01"))
            assertThat(get(token, q).statusCode()).isEqualTo(400);
        assertThat(listados.get()).isZero();
    }

    @Test
    void totalDePaginacionEsObligatorio() throws Exception {
        total = null;
        assertThat(get(token, "").statusCode()).isEqualTo(502);
    }

    @Test
    void detalleBuscaPaginasDelPaciente() throws Exception {
        var ajena = reserva("CONFIRMED");
        ajena.put("reservationProcessId", "process-anterior");
        var filas = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) filas.add(ajena);
        lista = json.writeValueAsString(filas);
        segundaPagina = json.writeValueAsString(List.of(reserva("CONFIRMED")));
        total = "101";
        assertThat(get(token, "/process-fixture").statusCode()).isEqualTo(200);
        assertThat(listados.get()).isEqualTo(2);
    }

    @Test
    void cancelacionPorKafkaSeGuardaYNoRetrocede() throws Exception {
        var e = new LinkedHashMap<String, Object>();
        e.put("eventId", "cancel-event");
        e.put("eventType", "AppointmentCancelled");
        e.put("schemaVersion", 1);
        e.put("groupId", "proyecto-fixture");
        e.put("reservationProcessId", "process-fixture");
        e.put("holdId", "hold-fixture");
        e.put("requestEventId", "request-fixture");
        e.put("reservationId", 5851);
        e.put("professionalId", 202);
        e.put("date", "2026-11-12");
        e.put("startTime", "18:00");
        e.put("status", "CANCELLED");
        e.put("occurredAt", AHORA);
        e.put("reason", null);
        kafka.recibir("process-fixture", json.writeValueAsString(e));
        assertThat(estado()).isEqualTo("CANCELADO");
        e.put("eventId", "late-confirmed");
        e.put("eventType", "AppointmentConfirmed");
        e.put("status", "CONFIRMED");
        kafka.recibir("process-fixture", json.writeValueAsString(e));
        assertThat(estado()).isEqualTo("CANCELADO");
    }

    @Test
    void guardadoFallidoDespuesDelExitoSeRecuperaPorConsulta() throws Exception {
        jdbc.execute(
                "alter table proceso_reserva add constraint fallo_cancel_fixture check(cancelado_en"
                        + " is null)");
        try {
            assertThat(cancelar(token, "").statusCode()).isEqualTo(503);
            assertThat(estado()).isEqualTo("CANCELANDO");
        } finally {
            jdbc.execute("alter table proceso_reserva drop constraint fallo_cancel_fixture");
        }
        lista = json.writeValueAsString(List.of(reserva("CANCELLED")));
        assertThat(get(token, "/process-fixture").statusCode()).isEqualTo(200);
        assertThat(estado()).isEqualTo("CANCELADO");
        assertThat(cancelaciones.get()).isEqualTo(1);
    }

    @Test
    void resultadoFailedActualizaProceso() throws Exception {
        pendiente("TELEFONO_ENVIADO");
        lista = json.writeValueAsString(List.of(reserva("FAILED")));
        assertThat(get(token, "/process-fixture").statusCode()).isEqualTo(200);
        assertThat(estado()).isEqualTo("PROCESO_INVALIDO");
    }

    @Test
    void paginaVaciaConservaTotalYNoExponeOtrosPacientes() throws Exception {
        lista = "[]";
        total = "0";
        assertThat(get(token, "").statusCode()).isEqualTo(200);
        assertThat(get(token, "/process-fixture").statusCode()).isEqualTo(404);
    }

    @Test
    void reintentoTrasIncertidumbreConsultaAntesDeReenviar() throws Exception {
        statusCancelacion = 503;
        respuestaCancelacion = "{}";
        cancelar(token, "");
        assertThat(estado()).isEqualTo("CANCELACION_DESCONOCIDA");
        statusCancelacion = 200;
        respuestaCancelacion =
                "{\"reservationId\":5851,\"reservationProcessId\":\"process-fixture\",\"status\":\"CANCELLED\",\"cancelledAt\":\"2026-11-10T21:00:00Z\"}";
        int antes = listados.get();
        assertThat(cancelar(token, "").statusCode()).isEqualTo(200);
        assertThat(listados.get()).isGreaterThan(antes);
        assertThat(estado()).isEqualTo("CANCELADO");
    }

    @Test
    void mismatchDeSlotNoSeExpone() throws Exception {
        var m = reserva("CONFIRMED");
        m.put("professionalId", 999);
        lista = json.writeValueAsString(List.of(m));
        assertThat(get(token, "/process-fixture").statusCode()).isEqualTo(502);
        assertThat(cancelaciones.get()).isZero();
    }

    @Autowired ar.com.modarelli.turnos.domain.ports.in.RecuperacionUseCase recuperacion;

    void pendiente(String estado) {
        jdbc.update(
                "update proceso_reserva set estado=?,actualizado_en=? where id=?",
                estado,
                Timestamp.from(AHORA.minusSeconds(120)),
                id);
    }

    @Test
    void recuperaConfirmacionPerdidaSinReenviar() {
        pendiente("CONFIRMACION_DESCONOCIDA");
        recuperacion.recuperar();
        assertThat(
                        jdbc.queryForObject(
                                "select estado from proceso_reserva where id=?", String.class, id))
                .isEqualTo("CONFIRMADO");
        assertThat(cancelaciones.get()).isZero();
        assertThat(query.get()).contains(paciente);
    }

    @Test
    void recuperaCancelacionPerdidaConFechaOriginal() {
        pendiente("CANCELACION_DESCONOCIDA");
        lista = json.writeValueAsString(List.of(reserva("CANCELLED")));
        recuperacion.recuperar();
        assertThat(
                        jdbc.queryForObject(
                                "select estado from proceso_reserva where id=?", String.class, id))
                .isEqualTo("CANCELADO");
        assertThat(cancelaciones.get()).isZero();
    }

    @Test
    void ausenciaNoEquivaleARechazo() {
        pendiente("CONFIRMACION_DESCONOCIDA");
        lista = "[]";
        total = "0";
        recuperacion.recuperar();
        assertThat(
                        jdbc.queryForObject(
                                "select estado from proceso_reserva where id=?", String.class, id))
                .isEqualTo("CONFIRMACION_DESCONOCIDA");
        assertThat(
                        jdbc.queryForObject(
                                "select recuperacion_resultado from proceso_reserva where id=?",
                                String.class,
                                id))
                .isEqualTo("NO_VERIFICABLE");
    }

    @Test
    void noInventaIdentificadorDeBloqueoPerdido() {
        pendiente("RESULTADO_DESCONOCIDO");
        jdbc.update(
                "update proceso_reserva set reservation_process_id=null,hold_id=null where id=?",
                id);
        recuperacion.recuperar();
        assertThat(listados.get()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select estado from proceso_reserva where id=?", String.class, id))
                .isEqualTo("RESULTADO_DESCONOCIDO");
    }

    @Test
    void falloPermiteOtroIntentoTrasReinicioPeroRespetaEspera() {
        pendiente("CONFIRMACION_DESCONOCIDA");
        statusLista = 503;
        recuperacion.recuperar();
        recuperacion.recuperar();
        assertThat(listados.get()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select recuperacion_resultado from proceso_reserva where id=?",
                                String.class,
                                id))
                .isEqualTo("CONSULTA_FALLIDA");
        jdbc.update(
                "update proceso_reserva set recuperacion_en=? where id=?",
                Timestamp.from(AHORA.minusSeconds(61)),
                id);
        statusLista = 200;
        recuperacion.recuperar();
        assertThat(
                        jdbc.queryForObject(
                                "select estado from proceso_reserva where id=?", String.class, id))
                .isEqualTo("CONFIRMADO");
    }

    @Test
    void noConsultaProcesosFinalizados() {
        recuperacion.recuperar();
        assertThat(listados.get()).isZero();
    }

    @Test
    void phonePendingEsperaCorrelacionKafkaSinInventarla() {
        pendiente("CONFIRMACION_DESCONOCIDA");
        jdbc.update("update proceso_reserva set solicitud_telefono_id=null where id=?", id);
        lista = json.writeValueAsString(List.of(reserva("PHONE_PENDING")));
        recuperacion.recuperar();
        assertThat(
                        jdbc.queryForObject(
                                "select estado from proceso_reserva where id=?", String.class, id))
                .isEqualTo("CONFIRMACION_DESCONOCIDA");
        assertThat(
                        jdbc.queryForObject(
                                "select solicitud_telefono_id from proceso_reserva where id=?",
                                String.class,
                                id))
                .isNull();
    }

    @Test
    void lecturaFallidaNoHaceRetrocederConfirmadoPorKafka() throws Exception {
        lista = json.writeValueAsString(List.of(reserva("FAILED")));
        get(token, "");
        assertThat(
                        jdbc.queryForObject(
                                "select estado from proceso_reserva where id=?", String.class, id))
                .isEqualTo("CONFIRMADO");
    }

    @Test
    void recuperaConfirmacionInterrumpidaDuranteReinicio() {
        pendiente("CONFIRMANDO");
        recuperacion.recuperar();
        assertThat(
                        jdbc.queryForObject(
                                "select estado from proceso_reserva where id=?", String.class, id))
                .isEqualTo("CONFIRMADO");
    }

    @Test
    void limitaPaginacionSinSuponerFracaso() {
        pendiente("CONFIRMACION_DESCONOCIDA");
        lista = lista.replace("process-fixture", "process-otra-reserva");
        total = "1001";
        recuperacion.recuperar();
        assertThat(listados.get()).isEqualTo(10);
        assertThat(estado()).isEqualTo("CONFIRMACION_DESCONOCIDA");
        assertThat(
                        jdbc.queryForObject(
                                "select recuperacion_resultado from proceso_reserva where id=?",
                                String.class,
                                id))
                .isEqualTo("LIMITE_CONSULTA");
    }

    @Test
    void vencimientoDelHoldNoCierraConfirmacionIncierta() {
        pendiente("CONFIRMACION_DESCONOCIDA");
        lista = "[]";
        total = "0";
        jdbc.update(
                "update proceso_reserva set vence_en=? where id=?",
                Timestamp.from(AHORA.minusSeconds(1)),
                id);
        recuperacion.recuperar();
        assertThat(estado()).isEqualTo("CONFIRMACION_DESCONOCIDA");
    }

    @Test
    void envioInterrumpidoSinRespuestaPermaneceDesconocido() {
        pendiente("SOLICITANDO");
        jdbc.update(
                "update proceso_reserva set reservation_process_id=null,hold_id=null where id=?",
                id);
        recuperacion.recuperar();
        assertThat(estado()).isEqualTo("RESULTADO_DESCONOCIDO");
        assertThat(listados.get()).isZero();
    }
}
