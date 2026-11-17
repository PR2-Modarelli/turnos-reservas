package ar.com.modarelli.turnos.domain.ports.out;

import ar.com.modarelli.turnos.domain.model.EventoTurnos;

import java.util.Map;

public interface CodecEventosPort {
    EventoTurnos leer(String contenido);

    String escribir(Map<String, Object> datos);
}
