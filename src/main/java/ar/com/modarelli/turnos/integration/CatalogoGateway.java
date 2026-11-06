package ar.com.modarelli.turnos.integration;
import ar.com.modarelli.turnos.integration.Contratos.Agenda;
public interface CatalogoGateway { Agenda agenda(long profesionalId, String jwtUsuario); }
