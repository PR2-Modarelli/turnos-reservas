package ar.com.modarelli.turnos.web;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

@org.springframework.context.annotation.Import(ar.com.modarelli.turnos.PostgresPruebas.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.password=fixture",
    "turnos.jwt-secret=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==",
    "turnos.catedra-url=", "turnos.catedra-token="})
class SeguridadHttpTest {
    @Value("${local.server.port}") int puerto;
    String token(String role,long expiracion,String clave) throws Exception {
        var b64=Base64.getUrlEncoder().withoutPadding();
        String header=b64.encodeToString("{\"alg\":\"HS512\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload=b64.encodeToString(("{\"sub\":\"usuario\",\"auth\":\""+role+"\",\"iat\":"+(Instant.now().getEpochSecond()-10)+",\"exp\":"+expiracion+"}").getBytes(StandardCharsets.UTF_8));
        String datos=header+"."+payload;
        Mac mac=Mac.getInstance("HmacSHA512");mac.init(new SecretKeySpec(clave.getBytes(StandardCharsets.UTF_8),"HmacSHA512"));
        return datos+"."+b64.encodeToString(mac.doFinal(datos.getBytes(StandardCharsets.UTF_8)));
    }
    int llamar(String ruta,String jwt) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://localhost:"+puerto+ruta));
        if(jwt!=null) req.header("Authorization","Bearer "+jwt);
        return HttpClient.newHttpClient().send(req.GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode();
    }
    @Test void sinTokenEs401() throws Exception {assertThat(llamar("/api/turnos/disponibilidad?profesionalId=15&fecha=2026-11-09",null)).isEqualTo(401);}
    @Test void firmaIncorrectaEs401() throws Exception {assertThat(llamar("/api/turnos/disponibilidad",token("ROLE_USER",Instant.now().plusSeconds(600).getEpochSecond(),"b".repeat(64)))).isEqualTo(401);}
    @Test void tokenVencidoEs401() throws Exception {assertThat(llamar("/api/turnos/disponibilidad",token("ROLE_USER",Instant.now().minusSeconds(120).getEpochSecond(),"a".repeat(64)))).isEqualTo(401);}
    @Test void rolTecnicoNoEsUsuario() throws Exception {assertThat(llamar("/api/turnos/disponibilidad",token("ROLE_STUDENT_CLIENT",Instant.now().plusSeconds(600).getEpochSecond(),"a".repeat(64)))).isEqualTo(403);}
    @Test void usuarioValidoLlegaALaValidacion() throws Exception {assertThat(llamar("/api/turnos/disponibilidad?profesionalId=0&fecha=2026-11-09",token("ROLE_USER",Instant.now().plusSeconds(600).getEpochSecond(),"a".repeat(64)))).isEqualTo(400);}
    @Test void saludPublica() throws Exception {assertThat(llamar("/actuator/health",null)).isEqualTo(200);}
    String sinCampo(String payload) throws Exception {
        var b64=Base64.getUrlEncoder().withoutPadding();
        String datos=b64.encodeToString("{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8))+"."+
            b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac=Mac.getInstance("HmacSHA512");mac.init(new SecretKeySpec("a".repeat(64).getBytes(StandardCharsets.UTF_8),"HmacSHA512"));
        return datos+"."+b64.encodeToString(mac.doFinal(datos.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void sinVencimientoSeRechaza() throws Exception {
        assertThat(llamar("/api/turnos/disponibilidad",sinCampo("{\"sub\":\"usuario\",\"auth\":\"ROLE_USER\"}"))).isEqualTo(401);
    }
    @Test void sinIdentidadSeRechaza() throws Exception {
        assertThat(llamar("/api/turnos/disponibilidad",sinCampo("{\"exp\":"+Instant.now().plusSeconds(600).getEpochSecond()+",\"auth\":\"ROLE_USER\"}"))).isEqualTo(401);
    }
}
