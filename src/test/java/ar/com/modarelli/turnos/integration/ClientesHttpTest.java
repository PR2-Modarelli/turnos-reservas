package ar.com.modarelli.turnos.integration;

import ar.com.modarelli.turnos.service.ErrorTurnos;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

class ClientesHttpTest {
    HttpServer servidor;
    ClientesHttp cliente;
    final AtomicReference<String> ruta=new AtomicReference<>(),auth=new AtomicReference<>();
    String cuerpo; int estado;
    @BeforeEach void iniciar() throws Exception {
        estado=200;
        servidor=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        servidor.createContext("/", exchange -> {
            ruta.set(exchange.getRequestURI().toString());auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=cuerpo.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(estado,bytes.length);
            try(var out=exchange.getResponseBody()) {out.write(bytes);}
        });
        servidor.start();
        String base="http://127.0.0.1:"+servidor.getAddress().getPort();
        cliente=new ClientesHttp(base,base,"tecnico-fixture",RestClient.builder());
    }
    @AfterEach void cerrar() {servidor.stop(0);}
    @Test void propagaJwtUsuarioSoloAlCatalogoYAceptaCamposNuevos() {
        cuerpo="""
        {"version":54,"profesional":{"id":15,"nombre":"Ana","apellido":"Torres","habilitado":true},
         "categoriaHabilitada":true,"horarios":[],"campoNuevo":"compatible"}
        """;
        assertThat(cliente.agenda(15,"usuario-fixture").version()).isEqualTo(54);
        assertThat(ruta.get()).isEqualTo("/api/catalogo/profesionales/15/agenda");
        assertThat(auth.get()).isEqualTo("Bearer usuario-fixture");
    }
    @Test void ocupacionesUsanJwtTecnicoYFechasContractuales() {
        cuerpo="""
        {"professionalId":15,"from":"2026-11-09","to":"2026-11-09",
         "occupancies":[{"date":"2026-11-09","startTime":"09:30","status":"CONFIRMED","expiresAt":null}]}
        """;
        assertThat(cliente.consultar(15,LocalDate.of(2026,11,9)).occupancies()).hasSize(1);
        assertThat(auth.get()).isEqualTo("Bearer tecnico-fixture");
        assertThat(ruta.get()).isEqualTo("/api/appointment-occupancies?professionalId=15&from=2026-11-09&to=2026-11-09");
    }
    @Test void errorRemotoNoExponeSuCuerpoNiCredenciales() {
        estado=500; cuerpo="secreto remoto";
        assertThatThrownBy(()->cliente.agenda(15,"usuario-fixture")).isInstanceOf(ErrorTurnos.class)
            .hasMessage("No se pudo consultar una dependencia");
    }
    @Test void profesionalInexistenteSeDistingueDeCaida() {
        estado=404;cuerpo="{}";
        assertThatThrownBy(()->cliente.agenda(99,"usuario-fixture")).isInstanceOfSatisfying(ErrorTurnos.class,
            e->assertThat(e.code()).isEqualTo("PROFESIONAL_NO_ENCONTRADO"));
    }
}
