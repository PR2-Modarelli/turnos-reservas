package ar.com.modarelli.turnos.integration;
import ar.com.modarelli.turnos.integration.Contratos.Ocupaciones;
import java.time.LocalDate;
public interface OcupacionesGateway { Ocupaciones consultar(long profesionalId, LocalDate fecha); }
