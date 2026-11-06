package ar.com.modarelli.turnos.web;

import ar.com.modarelli.turnos.PostgresPruebas;
import ar.com.modarelli.turnos.auth.UsuarioRepository;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Import(PostgresPruebas.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.password=fixture",
    "turnos.jwt-secret=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==",
    "turnos.catedra-url=", "turnos.catedra-token="})
class CuentasHttpTest {
    @Value("${local.server.port}") int puerto;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired UsuarioRepository usuarios;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient cliente=HttpClient.newHttpClient();
    @BeforeEach void limpiar() {jdbc.update("delete from app_user");}
    String registro(String login,String email) {
        return json.writeValueAsString(Map.of("login",login,"email",email,"password","clave-fixture",
            "firstName","Maria Eva","lastName","Modarelli","langKey","es"));
    }
    HttpResponse<String> post(String ruta,String body) throws Exception {
        return cliente.send(HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+ruta))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> cuenta(String token) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+"/api/account"));
        if(token!=null)req.header("Authorization","Bearer "+token);
        return cliente.send(req.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    String login(String usuario,String password,boolean rememberMe) throws Exception {
        return json.writeValueAsString(Map.of("username",usuario,"password",password,"rememberMe",rememberMe));
    }
    String token(String usuario) throws Exception {
        var respuesta=post("/api/authenticate",login(usuario,"clave-fixture",false));
        assertThat(respuesta.statusCode()).isEqualTo(200);
        return json.readTree(respuesta.body()).get("id_token").asText();
    }
    @Test void registraNormalizaYGuardaHashSaladoSinExponerlo() throws Exception {
        var respuesta=post("/api/register",registro("Eva.Prueba","EVA@EXAMPLE.COM"));
        assertThat(respuesta.statusCode()).isEqualTo(201);
        JsonNode body=json.readTree(respuesta.body());
        assertThat(body.get("login").asText()).isEqualTo("eva.prueba");
        assertThat(body.get("email").asText()).isEqualTo("eva@example.com");
        assertThat(body.get("activated").asBoolean()).isTrue();
        assertThat(body.get("authorities").get(0).asText()).isEqualTo("ROLE_USER");
        assertThat(respuesta.body()).doesNotContain("clave-fixture","passwordHash","password_hash");
        String hash=jdbc.queryForObject("select password_hash from app_user where login='eva.prueba'",String.class);
        assertThat(hash).isNotEqualTo("clave-fixture");assertThat(encoder.matches("clave-fixture",hash)).isTrue();
        post("/api/register",registro("otra","otra@example.com"));
        String otro=jdbc.queryForObject("select password_hash from app_user where login='otra'",String.class);
        assertThat(otro).isNotEqualTo(hash);
    }
    @Test void registroPuedeIngresarInmediatamenteYTokenTieneIdentidadEstable() throws Exception {
        post("/api/register",registro("eva","eva@example.com"));
        var respuesta=post("/api/authenticate",login("EVA","clave-fixture",false));
        assertThat(respuesta.statusCode()).isEqualTo(200);
        String token=json.readTree(respuesta.body()).get("id_token").asText();
        assertThat(respuesta.headers().firstValue("Authorization")).contains("Bearer "+token);
        var jwt=decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("eva");
        assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_USER");
        assertThat(jwt.getExpiresAt().getEpochSecond()-jwt.getIssuedAt().getEpochSecond()).isEqualTo(3600);
        assertThat(cuenta(token).statusCode()).isEqualTo(200);
        String id=json.readTree(cuenta(token).body()).get("publicId").asText();
        assertThat(jwt.getClaimAsString("uid")).isEqualTo(id);
        assertThat(decoder.decode(token("eva")).getClaimAsString("uid")).isEqualTo(id);
    }
    @Test void rememberMeDuraUnDiaSinOtorgarMasPermisos() throws Exception {
        post("/api/register",registro("eva","eva@example.com"));
        var respuesta=post("/api/authenticate",login("eva","clave-fixture",true));
        var jwt=decoder.decode(json.readTree(respuesta.body()).get("id_token").asText());
        assertThat(jwt.getExpiresAt().getEpochSecond()-jwt.getIssuedAt().getEpochSecond()).isEqualTo(86400);
        assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_USER");
    }
    @Test void loginInexistenteYClaveIncorrectaDanMismoError() throws Exception {
        post("/api/register",registro("eva","eva@example.com"));
        var inexistente=post("/api/authenticate",login("nadie","clave-fixture",false));
        var incorrecta=post("/api/authenticate",login("eva","otra-clave",false));
        assertThat(inexistente.statusCode()).isEqualTo(401);assertThat(incorrecta.statusCode()).isEqualTo(401);
        assertThat(json.readTree(inexistente.body()).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(json.readTree(incorrecta.body()).get("detail")).isEqualTo(json.readTree(inexistente.body()).get("detail"));
        assertThat(incorrecta.body()).doesNotContain("otra-clave","password_hash");
    }
    @Test void rechazaUsuariosInactivos() throws Exception {
        post("/api/register",registro("eva","eva@example.com"));String token=token("eva");
        jdbc.update("update app_user set activated=false where login='eva'");
        assertThat(post("/api/authenticate",login("eva","clave-fixture",false)).statusCode()).isEqualTo(401);
        assertThat(cuenta(token).statusCode()).isEqualTo(401);
    }
    @Test void noPermiteElegirRolActivacionNiId() throws Exception {
        for(String extra:List.of(
            ",\"authorities\":[\"ROLE_ADMIN\"]",",\"activated\":false",",\"id\":1")) {
            String datos=registro("eva","eva@example.com");
            assertThat(post("/api/register",datos.substring(0,datos.length()-1)+extra+"}").statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("select count(*) from app_user",Integer.class)).isZero();
    }
    @Test void unicidadNoDistingueMayusculas() throws Exception {
        post("/api/register",registro("eva","eva@example.com"));
        assertThat(post("/api/register",registro("EVA","otra@example.com")).statusCode()).isEqualTo(400);
        assertThat(post("/api/register",registro("otra","EVA@EXAMPLE.COM")).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from app_user",Integer.class)).isEqualTo(1);
    }
    @Test void validaDatosSinReflejarPasswordEnErrores() throws Exception {
        String valido=registro("eva","eva@example.com");
        for(String body:List.of(valido.replace("clave-fixture","abc"),valido.replace("eva@example.com","no-es-email"),
            valido.replace("\"eva\"","\"ab\""),valido.replace("Maria Eva","a".repeat(51)))) {
            var respuesta=post("/api/register",body);
            assertThat(respuesta.statusCode()).isEqualTo(400);
            assertThat(respuesta.body()).contains("VALIDATION_ERROR").doesNotContain("clave-fixture","password_hash");
        }
    }
    @Test void contrasenaLargaUnicodeNoSeTrunca() throws Exception {
        String password="á".repeat(90);
        String body=json.writeValueAsString(Map.of("login","eva","email","eva@example.com","password",password));
        assertThat(post("/api/register",body).statusCode()).isEqualTo(201);
        assertThat(post("/api/authenticate",login("eva",password,false)).statusCode()).isEqualTo(200);
        assertThat(post("/api/authenticate",login("eva","á".repeat(89)+"b",false)).statusCode()).isEqualTo(401);
    }
    @Test void dosRegistrosConcurrentesNoCreanDuplicados() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var inicio=new CountDownLatch(1);
            Callable<Integer> alta=()->{inicio.await();return post("/api/register",registro("eva","eva@example.com")).statusCode();};
            var a=executor.submit(alta);var b=executor.submit(alta);inicio.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,400);
            assertThat(jdbc.queryForObject("select count(*) from app_user",Integer.class)).isEqualTo(1);
        }
    }
    @Test void cadaUsuarioVeSoloSuCuenta() throws Exception {
        post("/api/register",registro("eva","eva@example.com"));post("/api/register",registro("otra","otra@example.com"));
        assertThat(json.readTree(cuenta(token("eva")).body()).get("login").asText()).isEqualTo("eva");
        assertThat(json.readTree(cuenta(token("otra")).body()).get("login").asText()).isEqualTo("otra");
        assertThat(cuenta(null).statusCode()).isEqualTo(401);
    }
    @Test void tokenDemoSinUsuarioPersistidoNoObtieneCuenta() throws Exception {
        String demo=new SeguridadHttpTest().token("ROLE_USER",Instant.now().plusSeconds(600).getEpochSecond(),"a".repeat(64));
        assertThat(cuenta(demo).statusCode()).isEqualTo(401);
    }
    @Test void migracionYDatosSonPropiosDeTurnos() throws Exception {
        post("/api/register",registro("eva","eva@example.com"));
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("turnos_prueba");
        assertThat(jdbc.queryForObject("select count(*) from databasechangelog where id='202611060001'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name='profesional'",Integer.class)).isZero();
        assertThat(usuarios.porLogin("eva")).isPresent();
    }
}
