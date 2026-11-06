package ar.com.modarelli.turnos.web;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@org.springframework.context.annotation.Import(ar.com.modarelli.turnos.PostgresPruebas.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.password=fixture",
    "turnos.jwt-secret=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==",
    "turnos.catedra-token=tecnico-fixture"})
class DisponibilidadHttpTest {
    static final LocalDate FECHA=LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).plusDays(1);
    static HttpServer externo;
    static volatile int statusOcupaciones=200;
    static final AtomicReference<String> tokenCatalogo=new AtomicReference<>(),tokenCatedra=new AtomicReference<>();
    @Value("${local.server.port}") int puerto;
    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry registro) throws Exception {
        externo=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        externo.createContext("/api/catalogo/profesionales/15/agenda", exchange -> {
            tokenCatalogo.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body="""
                {"version":54,"profesional":{"id":15,"nombre":"Ana","apellido":"Torres","habilitado":true},
                "categoriaHabilitada":true,"horarios":[{"id":1,"profesionalId":15,"diaSemana":"%s",
                "horaInicio":"09:00","horaFin":"11:00:00","duracionTurnoMinutos":30,"habilitado":true}]}
                """.formatted(FECHA.getDayOfWeek());
            responder(exchange,200,body);
        });
        externo.createContext("/api/appointment-occupancies", exchange -> {
            tokenCatedra.set(exchange.getRequestHeaders().getFirst("Authorization"));
            responder(exchange,statusOcupaciones,"""
                {"professionalId":15,"from":"%s","to":"%s","occupancies":[
                {"date":"%s","startTime":"09:30:00","status":"CONFIRMED","expiresAt":null}]}
                """.formatted(FECHA,FECHA,FECHA));
        });
        externo.start();
        String url="http://127.0.0.1:"+externo.getAddress().getPort();
        registro.add("turnos.catalogo-url",()->url);registro.add("turnos.catedra-url",()->url);
    }
    static void responder(com.sun.net.httpserver.HttpExchange exchange,int estado,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(estado,bytes.length);
        try(var out=exchange.getResponseBody()){out.write(bytes);}
    }
    @AfterAll static void cerrar(){externo.stop(0);}
    @BeforeEach void reiniciar(){statusOcupaciones=200;tokenCatalogo.set(null);tokenCatedra.set(null);}
    HttpResponse<String> consultar(String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+
            "/api/turnos/disponibilidad?profesionalId=15&fecha="+FECHA))
            .header("Authorization","Bearer "+token).GET().build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
    }
    String token() throws Exception {return new SeguridadHttpTest().token("ROLE_USER",Instant.now().plusSeconds(600).getEpochSecond(),"a".repeat(64));}
    @Test void consultaHttpCompletaValidaFirmaYDescuentaOcupaciones() throws Exception {
        String jwt=token();var response=consultar(jwt);
        assertThat(response.statusCode()).isEqualTo(200);
        var body=JsonMapper.builder().build().readTree(response.body());
        assertThat(body.get("versionCatalogo").asLong()).isEqualTo(54);
        assertThat(body.get("turnos").size()).isEqualTo(3);
        assertThat(body.get("turnos").get(1).get("horaInicio").asText()).isEqualTo("10:00:00");
        assertThat(tokenCatalogo.get()).isEqualTo("Bearer "+jwt);
        assertThat(tokenCatedra.get()).isEqualTo("Bearer tecnico-fixture");
    }
    @Test void caidaDeCatedraNoSePresentaComoAgendaLibre() throws Exception {
        statusOcupaciones=503;var response=consultar(token());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("DEPENDENCIA_NO_DISPONIBLE").doesNotContain("tecnico-fixture");
    }
    @Test void usuarioRegistradoIniciaSesionYConsultaDisponibilidad() throws Exception {
        var client=HttpClient.newHttpClient();
        String nombre="usuario."+java.util.UUID.randomUUID();
        String registro="{\"login\":\""+nombre+"\",\"password\":\"clave-fixture\",\"email\":\""+nombre+"@example.com\"}";
        var alta=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+"/api/register"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(registro)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(alta.statusCode()).isEqualTo(201);
        String datos="{\"username\":\""+nombre+"\",\"password\":\"clave-fixture\"}";
        var ingreso=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+"/api/authenticate"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(datos)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(ingreso.statusCode()).isEqualTo(200);
        String jwt=JsonMapper.builder().build().readTree(ingreso.body()).get("id_token").asText();
        var response=consultar(jwt);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonMapper.builder().build().readTree(response.body()).get("turnos").size()).isEqualTo(3);
        assertThat(tokenCatalogo.get()).isEqualTo("Bearer "+jwt);
        assertThat(tokenCatedra.get()).isEqualTo("Bearer tecnico-fixture");
    }
}
