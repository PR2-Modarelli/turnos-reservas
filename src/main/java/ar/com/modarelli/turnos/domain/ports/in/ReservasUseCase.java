package ar.com.modarelli.turnos.domain.ports.in;

import ar.com.modarelli.turnos.domain.model.*;
import ar.com.modarelli.turnos.domain.model.BloqueoDtos.*;
import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;
import ar.com.modarelli.turnos.domain.model.DisponibilidadDtos.*;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;

import java.time.*;
import java.util.*;

public interface ReservasUseCase {
    Pagina listar(
            String estado,
            LocalDate desde,
            LocalDate hasta,
            int pagina,
            int tamanio,
            IdentidadUsuario identidad);

    Reserva detalle(String proceso, IdentidadUsuario identidad);

    ResultadoCancelacion cancelar(String proceso, String motivo, IdentidadUsuario identidad);
}
