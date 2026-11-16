package ar.com.modarelli.turnos.domain.ports.out;

import ar.com.modarelli.turnos.domain.model.Contratos.Ocupaciones;

import java.time.LocalDate;

public interface OcupacionesGateway {
    Ocupaciones consultar(long profesionalId, LocalDate fecha);
}
