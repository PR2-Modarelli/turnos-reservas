package ar.com.modarelli.turnos.domain.ports.in;

import ar.com.modarelli.turnos.domain.model.*;
import ar.com.modarelli.turnos.domain.model.BloqueoDtos.*;
import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;
import ar.com.modarelli.turnos.domain.model.DisponibilidadDtos.*;
import ar.com.modarelli.turnos.domain.ports.out.ReservasGateway.*;

import java.time.*;
import java.util.*;

public interface CuentaUseCase {
    Cuenta registrar(Registro datos);

    Token autenticar(Credenciales datos);

    Cuenta cuenta(IdentidadUsuario identidad);

    Cuenta actualizarNombres(Nombres nombres, IdentidadUsuario identidad);
}
