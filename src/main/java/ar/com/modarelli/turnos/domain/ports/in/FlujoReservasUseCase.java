package ar.com.modarelli.turnos.domain.ports.in;

import ar.com.modarelli.turnos.domain.model.*;
import ar.com.modarelli.turnos.domain.model.BloqueoDtos.*;
import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;
import ar.com.modarelli.turnos.domain.model.DisponibilidadDtos.*;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;

import java.time.*;
import java.util.*;

public interface FlujoReservasUseCase {
    void recibir(String clave, String payload);

    void reprocesar(String id);

    Map<String, Object> informacion(UUID id, IdentidadUsuario identidad);

    Map<String, Object> telefono(UUID id, UUID clave, String telefono, IdentidadUsuario identidad);
}
