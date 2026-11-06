package ar.com.modarelli.turnos.integration;

import ar.com.modarelli.turnos.integration.Contratos.*;
import ar.com.modarelli.turnos.service.ErrorTurnos;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class ClientesHttp implements CatalogoGateway, OcupacionesGateway {
    private final RestClient cliente;
    private final String catalogoUrl, catedraUrl, catedraToken;
    @org.springframework.beans.factory.annotation.Autowired
    public ClientesHttp(@Value("${turnos.catalogo-url}") String catalogo,
                        @Value("${turnos.catedra-url}") String catedra,
                        @Value("${turnos.catedra-token}") String token) {
        this(catalogo, catedra, token, builder());
    }
    ClientesHttp(String catalogo, String catedra, String token, RestClient.Builder builder) {
        catalogoUrl=catalogo; catedraUrl=catedra; catedraToken=token; cliente=builder.build();
    }
    private static RestClient.Builder builder() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory);
    }
    private URI uri(String base, String ruta) {
        if (base.isBlank()) throw new ErrorTurnos(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRACION_NO_CONFIGURADA", "Falta configurar una dependencia");
        try {
            URI uri = URI.create(base.replaceAll("/+$", "") + ruta);
            if (!uri.isAbsolute() || uri.getHost()==null || uri.getUserInfo()!=null ||
                !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ErrorTurnos(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRACION_NO_CONFIGURADA", "Configuracion de integracion invalida");
        }
    }
    @Override public Agenda agenda(long id, String jwt) {
        try {
            Agenda agenda = cliente.get().uri(uri(catalogoUrl, "/api/catalogo/profesionales/"+id+"/agenda"))
                .headers(h -> h.setBearerAuth(jwt)).retrieve().body(Agenda.class);
            if (agenda==null) throw invalida();
            return agenda;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value()==404) throw new ErrorTurnos(HttpStatus.NOT_FOUND, "PROFESIONAL_NO_ENCONTRADO", "Profesional inexistente");
            throw noDisponible();
        } catch (RestClientException e) { throw noDisponible(); }
    }
    @Override public Ocupaciones consultar(long id, LocalDate fecha) {
        if (catedraToken.isBlank()) throw new ErrorTurnos(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRACION_NO_CONFIGURADA", "Falta configurar la integracion con catedra");
        try {
            Ocupaciones respuesta = cliente.get().uri(uri(catedraUrl,
                "/api/appointment-occupancies?professionalId="+id+"&from="+fecha+"&to="+fecha))
                .headers(h -> h.setBearerAuth(catedraToken)).retrieve().body(Ocupaciones.class);
            if (respuesta==null) throw invalida();
            return respuesta;
        } catch (RestClientException e) { throw noDisponible(); }
    }
    private ErrorTurnos noDisponible() { return new ErrorTurnos(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCIA_NO_DISPONIBLE", "No se pudo consultar una dependencia"); }
    private ErrorTurnos invalida() { return new ErrorTurnos(HttpStatus.BAD_GATEWAY, "RESPUESTA_EXTERNA_INVALIDA", "Una dependencia devolvio datos invalidos"); }
}
