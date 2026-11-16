package ar.com.modarelli.turnos.domain.ports.out;

import ar.com.modarelli.turnos.domain.model.Contratos.Agenda;

public interface CatalogoGateway {
    Agenda agenda(long profesionalId, String jwtUsuario);
}
