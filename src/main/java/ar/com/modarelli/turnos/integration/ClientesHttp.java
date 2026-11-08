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
public class ClientesHttp implements CatalogoGateway, OcupacionesGateway, HoldGateway, ConfirmacionGateway {
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
    @Override public void exigirConfiguracionHold() {
        if(catedraToken.isBlank()) throw new ErrorTurnos(HttpStatus.SERVICE_UNAVAILABLE,
            "INTEGRACION_NO_CONFIGURADA","Falta configurar la integracion con catedra");
        uri(catedraUrl,"/api/appointment-holds");
    }
    @Override public HoldRespuesta crearHold(HoldSolicitud solicitud) {
        exigirConfiguracionHold();
        try {
            var respuesta=cliente.post().uri(uri(catedraUrl,"/api/appointment-holds"))
                .headers(h->h.setBearerAuth(catedraToken)).contentType(MediaType.APPLICATION_JSON)
                .body(solicitud).retrieve().toEntity(HoldRespuesta.class);
            HoldRespuesta body=respuesta.getBody();
            if(respuesta.getStatusCode().value()!=201 || body==null || !"HELD".equals(body.status()) ||
                !idValido(body.holdId()) || !idValido(body.reservationProcessId()) || body.expiresAt()==null)
                throw incierto();
            return body;
        } catch(RestClientResponseException e) {
            int http=e.getStatusCode().value();
            if(http==401 || http==403) throw new ErrorHoldRemoto(false,HttpStatus.SERVICE_UNAVAILABLE,"INTEGRACION_NO_AUTORIZADA");
            String code=null;
            try { var error=e.getResponseBodyAs(ProblemaHold.class);if(error!=null)code=error.code(); }
            catch(RestClientException | IllegalArgumentException ignored) { /* No exponer el body externo. */ }
            boolean rechazo=(http==400 && java.util.Set.of("VALIDATION_ERROR","PROFESSIONAL_DISABLED","INVALID_SLOT").contains(code==null?"":code)) ||
                (http==404 && "PROFESSIONAL_NOT_FOUND".equals(code)) ||
                (http==409 && ("SLOT_ALREADY_HELD".equals(code) || "SLOT_ALREADY_RESERVED".equals(code)));
            if(rechazo) throw new ErrorHoldRemoto(false,HttpStatus.valueOf(http),code);
            throw incierto();
        } catch(RestClientException e) { throw incierto(); }
    }
    @Override public RespuestaConfirmacion confirmarHold(String holdId,String procesoRemoto,SolicitudConfirmacion solicitud) {
        exigirConfiguracionHold();
        String segmento=java.net.URLEncoder.encode(holdId,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        try {
            var respuesta=cliente.post().uri(uri(catedraUrl,"/api/appointment-holds/"+segmento+"/confirm"))
                .headers(h->h.setBearerAuth(catedraToken)).contentType(MediaType.APPLICATION_JSON)
                .body(solicitud).retrieve().toEntity(RespuestaConfirmacion.class);
            var body=respuesta.getBody();
            if(respuesta.getStatusCode().value()!=202 || body==null || !procesoRemoto.equals(body.reservationProcessId()) ||
                !"WAITING_FOR_PHONE".equals(body.status()) || body.expiresAt()==null)throw confirmacionIncierta("RESULTADO_CONFIRMACION_DESCONOCIDO");
            return body;
        }catch(RestClientResponseException e) {
            int http=e.getStatusCode().value();String code=null;
            try{var error=e.getResponseBodyAs(ProblemaHold.class);if(error!=null)code=error.code();}
            catch(RestClientException | IllegalArgumentException ignored){ /* Sanitizar respuesta externa. */ }
            String seguro=code==null?"":code;
            if(http==409 && "RESERVATION_PROCESS_ALREADY_CONFIRMED".equals(seguro))
                throw confirmacionIncierta("CONFIRMACION_PREVIA_NO_VERIFICADA");
            boolean rechazo=(http==400 && java.util.Set.of("VALIDATION_ERROR","INVALID_SLOT","EXTERNAL_PATIENT_MISMATCH").contains(seguro)) ||
                (http==404 && java.util.Set.of("PROFESSIONAL_NOT_FOUND","HOLD_NOT_FOUND","HOLD_EXPIRED").contains(seguro)) ||
                (http==409 && "SLOT_ALREADY_RESERVED".equals(seguro)) || (http==403 && "HOLD_OWNER_MISMATCH".equals(seguro));
            if(rechazo)throw new ErrorConfirmacionRemota(false,HttpStatus.valueOf(http),seguro);
            if(http==401 || http==403)throw new ErrorConfirmacionRemota(false,HttpStatus.SERVICE_UNAVAILABLE,"INTEGRACION_NO_AUTORIZADA");
            throw confirmacionIncierta("RESULTADO_CONFIRMACION_DESCONOCIDO");
        }catch(RestClientException e){throw confirmacionIncierta("RESULTADO_CONFIRMACION_DESCONOCIDO");}
    }
    private ErrorConfirmacionRemota confirmacionIncierta(String code) {
        return new ErrorConfirmacionRemota(true,HttpStatus.SERVICE_UNAVAILABLE,code);
    }
    private boolean idValido(String id) { return id!=null && !id.isBlank() && id.length()<=200; }
    private ErrorHoldRemoto incierto() {
        return new ErrorHoldRemoto(true,HttpStatus.SERVICE_UNAVAILABLE,"RESULTADO_HOLD_DESCONOCIDO");
    }
    private ErrorTurnos noDisponible() { return new ErrorTurnos(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCIA_NO_DISPONIBLE", "No se pudo consultar una dependencia"); }
    private ErrorTurnos invalida() { return new ErrorTurnos(HttpStatus.BAD_GATEWAY, "RESPUESTA_EXTERNA_INVALIDA", "Una dependencia devolvio datos invalidos"); }
}
