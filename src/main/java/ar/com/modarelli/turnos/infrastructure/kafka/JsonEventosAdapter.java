package ar.com.modarelli.turnos.infrastructure.kafka;

import ar.com.modarelli.turnos.domain.model.EventoTurnos;
import ar.com.modarelli.turnos.domain.ports.out.CodecEventosPort;

import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Component
public class JsonEventosAdapter implements CodecEventosPort {
    private final JsonMapper json =
            JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public EventoTurnos leer(String contenido) {
        return json.readValue(contenido, EventoTurnos.class);
    }

    public String escribir(Map<String, Object> datos) {
        return json.writeValueAsString(datos);
    }
}
