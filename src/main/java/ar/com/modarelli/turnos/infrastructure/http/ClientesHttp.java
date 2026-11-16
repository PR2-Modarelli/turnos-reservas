package ar.com.modarelli.turnos.infrastructure.http;

import ar.com.modarelli.turnos.domain.exception.ErrorConfirmacionRemota;
import ar.com.modarelli.turnos.domain.exception.ErrorHoldRemoto;
import ar.com.modarelli.turnos.domain.exception.ErrorTurnos;
import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;
import ar.com.modarelli.turnos.domain.model.Contratos.*;
import ar.com.modarelli.turnos.domain.ports.out.CatalogoGateway;
import ar.com.modarelli.turnos.domain.ports.out.ConfirmacionGateway;
import ar.com.modarelli.turnos.domain.ports.out.HoldGateway;
import ar.com.modarelli.turnos.domain.ports.out.OcupacionesGateway;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;

@Component
public class ClientesHttp
        implements CatalogoGateway,
                OcupacionesGateway,
                HoldGateway,
                ConfirmacionGateway,
                ReservasGateway {
    private final RestClient cliente;
    private final String catalogoUrl, catedraUrl, catedraToken;

    @org.springframework.beans.factory.annotation.Autowired
    public ClientesHttp(
            @Value("${turnos.catalogo-url}") String catalogo,
            @Value("${turnos.catedra-url}") String catedra,
            @Value("${turnos.catedra-token}") String token) {
        this(catalogo, catedra, token, builder());
    }

    ClientesHttp(String catalogo, String catedra, String token, RestClient.Builder builder) {
        catalogoUrl = catalogo;
        catedraUrl = catedra;
        catedraToken = token;
        cliente =
                builder.messageConverters(
                                converters -> {
                                    converters.removeIf(
                                            c ->
                                                    c
                                                            instanceof
                                                            org.springframework.http.converter.json
                                                                    .JacksonJsonHttpMessageConverter);
                                    converters.add(
                                            new org.springframework.http.converter.json
                                                    .JacksonJsonHttpMessageConverter(
                                                    tools.jackson.databind.json.JsonMapper.builder()
                                                            .disable(
                                                                    tools.jackson.databind
                                                                            .DeserializationFeature
                                                                            .FAIL_ON_UNKNOWN_PROPERTIES)
                                                            .build()));
                                })
                        .build();
    }

    private static RestClient.Builder builder() {
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .connectTimeout(Duration.ofSeconds(3))
                                .build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory);
    }

    private URI uri(String base, String ruta) {
        if (base.isBlank())
            throw new ErrorTurnos(
                    EstadoOperacion.SERVICE_UNAVAILABLE,
                    "INTEGRACION_NO_CONFIGURADA",
                    "Falta configurar una dependencia");
        try {
            URI uri = URI.create(base.replaceAll("/+$", "") + ruta);
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")))
                throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ErrorTurnos(
                    EstadoOperacion.SERVICE_UNAVAILABLE,
                    "INTEGRACION_NO_CONFIGURADA",
                    "Configuracion de integracion invalida");
        }
    }

    @Override
    public Agenda agenda(long id, String jwt) {
        try {
            Agenda agenda =
                    cliente.get()
                            .uri(uri(catalogoUrl, "/api/catalogo/profesionales/" + id + "/agenda"))
                            .headers(h -> h.setBearerAuth(jwt))
                            .retrieve()
                            .body(Agenda.class);
            if (agenda == null) throw invalida();
            return agenda;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404)
                throw new ErrorTurnos(
                        EstadoOperacion.NOT_FOUND,
                        "PROFESIONAL_NO_ENCONTRADO",
                        "Profesional inexistente");
            throw noDisponible();
        } catch (RestClientException e) {
            throw noDisponible();
        }
    }

    @Override
    public Ocupaciones consultar(long id, LocalDate fecha) {
        if (catedraToken.isBlank())
            throw new ErrorTurnos(
                    EstadoOperacion.SERVICE_UNAVAILABLE,
                    "INTEGRACION_NO_CONFIGURADA",
                    "Falta configurar la integracion con catedra");
        try {
            Ocupaciones respuesta =
                    cliente.get()
                            .uri(
                                    uri(
                                            catedraUrl,
                                            "/api/appointment-occupancies?professionalId="
                                                    + id
                                                    + "&from="
                                                    + fecha
                                                    + "&to="
                                                    + fecha))
                            .headers(h -> h.setBearerAuth(catedraToken))
                            .retrieve()
                            .body(Ocupaciones.class);
            if (respuesta == null) throw invalida();
            return respuesta;
        } catch (RestClientException e) {
            throw noDisponible();
        }
    }

    @Override
    public void exigirConfiguracionHold() {
        if (catedraToken.isBlank())
            throw new ErrorTurnos(
                    EstadoOperacion.SERVICE_UNAVAILABLE,
                    "INTEGRACION_NO_CONFIGURADA",
                    "Falta configurar la integracion con catedra");
        uri(catedraUrl, "/api/appointment-holds");
    }

    @Override
    public HoldRespuesta crearHold(HoldSolicitud solicitud) {
        exigirConfiguracionHold();
        try {
            var respuesta =
                    cliente.post()
                            .uri(uri(catedraUrl, "/api/appointment-holds"))
                            .headers(h -> h.setBearerAuth(catedraToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(solicitud)
                            .retrieve()
                            .toEntity(HoldRespuesta.class);
            HoldRespuesta body = respuesta.getBody();
            if (respuesta.getStatusCode().value() != 201
                    || body == null
                    || !"HELD".equals(body.status())
                    || !idValido(body.holdId())
                    || !idValido(body.reservationProcessId())
                    || body.expiresAt() == null) throw incierto();
            return body;
        } catch (RestClientResponseException e) {
            int http = e.getStatusCode().value();
            if (http == 401 || http == 403)
                throw new ErrorHoldRemoto(
                        false, EstadoOperacion.SERVICE_UNAVAILABLE, "INTEGRACION_NO_AUTORIZADA");
            String code = null;
            try {
                var error = e.getResponseBodyAs(ProblemaHold.class);
                if (error != null) code = error.code();
            } catch (RestClientException | IllegalArgumentException ignored) {
                /* No exponer el body externo. */
            }
            boolean rechazo =
                    (http == 400
                                    && java.util.Set.of(
                                                    "VALIDATION_ERROR",
                                                    "PROFESSIONAL_DISABLED",
                                                    "INVALID_SLOT")
                                            .contains(code == null ? "" : code))
                            || (http == 404 && "PROFESSIONAL_NOT_FOUND".equals(code))
                            || (http == 409
                                    && ("SLOT_ALREADY_HELD".equals(code)
                                            || "SLOT_ALREADY_RESERVED".equals(code)));
            if (rechazo) throw new ErrorHoldRemoto(false, EstadoOperacion.valueOf(http), code);
            throw incierto();
        } catch (RestClientException e) {
            throw incierto();
        }
    }

    @Override
    public RespuestaConfirmacion confirmarHold(
            String holdId, String procesoRemoto, SolicitudConfirmacion solicitud) {
        exigirConfiguracionHold();
        String segmento =
                java.net.URLEncoder.encode(holdId, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20");
        try {
            var respuesta =
                    cliente.post()
                            .uri(uri(catedraUrl, "/api/appointment-holds/" + segmento + "/confirm"))
                            .headers(h -> h.setBearerAuth(catedraToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(solicitud)
                            .retrieve()
                            .toEntity(RespuestaConfirmacion.class);
            var body = respuesta.getBody();
            if (respuesta.getStatusCode().value() != 202
                    || body == null
                    || !procesoRemoto.equals(body.reservationProcessId())
                    || !"WAITING_FOR_PHONE".equals(body.status())
                    || body.expiresAt() == null)
                throw confirmacionIncierta("RESULTADO_CONFIRMACION_DESCONOCIDO");
            return body;
        } catch (RestClientResponseException e) {
            int http = e.getStatusCode().value();
            String code = null;
            try {
                var error = e.getResponseBodyAs(ProblemaHold.class);
                if (error != null) code = error.code();
            } catch (RestClientException | IllegalArgumentException ignored) {
                /* Sanitizar respuesta externa. */
            }
            String seguro = code == null ? "" : code;
            if (http == 409 && "RESERVATION_PROCESS_ALREADY_CONFIRMED".equals(seguro))
                throw confirmacionIncierta("CONFIRMACION_PREVIA_NO_VERIFICADA");
            boolean rechazo =
                    (http == 400
                                    && java.util.Set.of(
                                                    "VALIDATION_ERROR",
                                                    "INVALID_SLOT",
                                                    "EXTERNAL_PATIENT_MISMATCH")
                                            .contains(seguro))
                            || (http == 404
                                    && java.util.Set.of(
                                                    "PROFESSIONAL_NOT_FOUND",
                                                    "HOLD_NOT_FOUND",
                                                    "HOLD_EXPIRED")
                                            .contains(seguro))
                            || (http == 409 && "SLOT_ALREADY_RESERVED".equals(seguro))
                            || (http == 403 && "HOLD_OWNER_MISMATCH".equals(seguro));
            if (rechazo)
                throw new ErrorConfirmacionRemota(false, EstadoOperacion.valueOf(http), seguro);
            if (http == 401 || http == 403)
                throw new ErrorConfirmacionRemota(
                        false, EstadoOperacion.SERVICE_UNAVAILABLE, "INTEGRACION_NO_AUTORIZADA");
            throw confirmacionIncierta("RESULTADO_CONFIRMACION_DESCONOCIDO");
        } catch (RestClientException e) {
            throw confirmacionIncierta("RESULTADO_CONFIRMACION_DESCONOCIDO");
        }
    }

    @Override
    public Pagina listarReservas(
            String paciente,
            String estado,
            LocalDate desde,
            LocalDate hasta,
            int pagina,
            int tamanio) {
        exigirConfiguracionHold();
        var query =
                org.springframework.web.util.UriComponentsBuilder.fromUri(
                                uri(catedraUrl, "/api/appointments"))
                        .queryParam("externalPatientId", paciente)
                        .queryParam("page", pagina)
                        .queryParam("size", tamanio);
        if (estado != null) query.queryParam("status", estado);
        if (desde != null) query.queryParam("from", desde);
        if (hasta != null) query.queryParam("to", hasta);
        try {
            var r =
                    cliente.get()
                            .uri(query.build().encode().toUri())
                            .headers(h -> h.setBearerAuth(catedraToken))
                            .retrieve()
                            .toEntity(Reserva[].class);
            String total = r.getHeaders().getFirst("X-Total-Count");
            if (r.getStatusCode().value() != 200 || r.getBody() == null || total == null)
                throw invalida();
            long n;
            try {
                n = Long.parseLong(total);
            } catch (NumberFormatException e) {
                throw invalida();
            }
            if (n < 0
                    || r.getBody().length > tamanio
                    || n < (long) pagina * tamanio + r.getBody().length && r.getBody().length > 0)
                throw invalida();
            var reservas = java.util.Arrays.asList(r.getBody());
            for (var reserva : reservas) {
                if (reserva == null
                        || !paciente.equals(reserva.externalPatientId())
                        || reserva.reservationId() == null
                        || reserva.reservationId() <= 0
                        || !idValido(reserva.reservationProcessId())
                        || reserva.professionalId() == null
                        || reserva.professionalId() <= 0
                        || reserva.appointmentDate() == null
                        || reserva.startTime() == null
                        || reserva.endTime() == null
                        || !reserva.endTime().isAfter(reserva.startTime())
                        || !java.util.Set.of("PHONE_PENDING", "CONFIRMED", "CANCELLED", "FAILED")
                                .contains(reserva.status() == null ? "" : reserva.status())
                        || ("CANCELLED".equals(reserva.status()) && reserva.cancelledAt() == null)
                        || (reserva.cancellationReason() != null
                                && reserva.cancellationReason().length() > 500)
                        || (estado != null && !estado.equals(reserva.status()))
                        || (desde != null && reserva.appointmentDate().isBefore(desde))
                        || (hasta != null && reserva.appointmentDate().isAfter(hasta)))
                    throw invalida();
            }
            return new Pagina(java.util.List.copyOf(reservas), n, pagina, tamanio);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
                throw integracionNoAutorizada();
            throw noDisponible();
        } catch (RestClientException e) {
            throw noDisponible();
        }
    }

    @Override
    public Cancelacion cancelarReserva(String proceso, Long reserva, String motivo) {
        exigirConfiguracionHold();
        String segmento =
                java.net.URLEncoder.encode(proceso, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20");
        try {
            var r =
                    cliente.post()
                            .uri(uri(catedraUrl, "/api/appointments/" + segmento + "/cancel"))
                            .headers(h -> h.setBearerAuth(catedraToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new Motivo(motivo))
                            .retrieve()
                            .toEntity(Cancelacion.class);
            var b = r.getBody();
            if (r.getStatusCode().value() != 200
                    || b == null
                    || !proceso.equals(b.reservationProcessId())
                    || !reserva.equals(b.reservationId())
                    || !"CANCELLED".equals(b.status())
                    || b.cancelledAt() == null) throw noDisponible();
            return b;
        } catch (RestClientResponseException e) {
            int h = e.getStatusCode().value();
            String code = "";
            try {
                var b = e.getResponseBodyAs(ProblemaHold.class);
                if (b != null && b.code() != null) code = b.code();
            } catch (RuntimeException ignored) {
            }
            if (h == 401 || h == 403) throw integracionNoAutorizada();
            if ((h == 409 && code.equals("RESERVATION_NOT_CANCELLABLE"))
                    || (h == 404 && code.equals("RESERVATION_NOT_FOUND"))
                    || (h == 400 && code.equals("VALIDATION_ERROR")))
                throw new ErrorTurnos(
                        EstadoOperacion.valueOf(h), code, "La catedra rechazo la cancelacion");
            throw noDisponible();
        } catch (RestClientException e) {
            throw noDisponible();
        }
    }

    private ErrorTurnos integracionNoAutorizada() {
        return new ErrorTurnos(
                EstadoOperacion.SERVICE_UNAVAILABLE,
                "INTEGRACION_NO_AUTORIZADA",
                "La integracion tecnica no esta autorizada");
    }

    private ErrorConfirmacionRemota confirmacionIncierta(String code) {
        return new ErrorConfirmacionRemota(true, EstadoOperacion.SERVICE_UNAVAILABLE, code);
    }

    private boolean idValido(String id) {
        return id != null && !id.isBlank() && id.length() <= 200;
    }

    private ErrorHoldRemoto incierto() {
        return new ErrorHoldRemoto(
                true, EstadoOperacion.SERVICE_UNAVAILABLE, "RESULTADO_HOLD_DESCONOCIDO");
    }

    private ErrorTurnos noDisponible() {
        return new ErrorTurnos(
                EstadoOperacion.SERVICE_UNAVAILABLE,
                "DEPENDENCIA_NO_DISPONIBLE",
                "No se pudo consultar una dependencia");
    }

    private ErrorTurnos invalida() {
        return new ErrorTurnos(
                EstadoOperacion.BAD_GATEWAY,
                "RESPUESTA_EXTERNA_INVALIDA",
                "Una dependencia devolvio datos invalidos");
    }
}
