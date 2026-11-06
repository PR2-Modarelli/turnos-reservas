package ar.com.modarelli.turnos.service;
import ar.com.modarelli.turnos.integration.*;
import ar.com.modarelli.turnos.integration.Contratos.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisponibilidadServicioTest {
    final CatalogoGateway catalogo=mock(CatalogoGateway.class);
    final OcupacionesGateway catedra=mock(OcupacionesGateway.class);
    final Clock reloj=Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"),ZoneId.of("America/Argentina/Mendoza"));
    final DisponibilidadServicio servicio=new DisponibilidadServicio(catalogo,catedra,reloj);
    final LocalDate fecha=LocalDate.of(2026,11,9);
    Horario regla(LocalTime inicio,LocalTime fin,int minutos,boolean activa) {
        return new Horario(1L,15L,DayOfWeek.MONDAY,inicio,fin,minutos,activa);
    }
    Agenda agenda(boolean habilitado,boolean categoria,List<Horario> reglas) {
        return new Agenda(54L,new Profesional(15L,"Ana","Torres",1L,"Clinica",habilitado),categoria,reglas);
    }
    void configurar(List<Horario> reglas,List<Ocupacion> ocupadas) {
        when(catalogo.agenda(15,"jwt-usuario")).thenReturn(agenda(true,true,reglas));
        when(catedra.consultar(15,fecha)).thenReturn(new Ocupaciones(15L,fecha,fecha,ocupadas));
    }
    @Test void combinaAgendaConHoldsYConfirmadasSinDuplicar() {
        var regla=regla(LocalTime.of(9,0),LocalTime.of(11,0),30,true);
        configurar(List.of(regla,regla),List.of(
            new Ocupacion(fecha,LocalTime.of(9,0),"CONFIRMED",null),
            new Ocupacion(fecha,LocalTime.of(9,30),"HELD",Instant.parse("2026-11-06T12:01:00Z"))));
        var resultado=servicio.consultar(15,fecha,"jwt-usuario");
        assertThat(resultado.versionCatalogo()).isEqualTo(54);
        assertThat(resultado.turnos()).extracting(DisponibilidadServicio.Turno::horaInicio)
            .containsExactly(LocalTime.of(10,0),LocalTime.of(10,30));
        assertThat(resultado.zonaHoraria()).isEqualTo("America/Argentina/Mendoza");
    }
    @Test void noGeneraTurnoQueDesbordeElFinNiCruzaMedianoche() {
        configurar(List.of(regla(LocalTime.of(23,0),LocalTime.of(23,59),40,true)),List.of());
        assertThat(servicio.consultar(15,fecha,"jwt-usuario").turnos()).hasSize(1)
            .first().extracting(DisponibilidadServicio.Turno::horaFin).isEqualTo(LocalTime.of(23,40));
    }
    @Test void profesionalDeshabilitadoNoConsultaCatedra() {
        when(catalogo.agenda(15,"jwt-usuario")).thenReturn(agenda(false,true,List.of()));
        assertThat(servicio.consultar(15,fecha,"jwt-usuario").turnos()).isEmpty();
        verifyNoInteractions(catedra);
    }
    @Test void categoriaDeshabilitadaNoOfreceAgenda() {
        when(catalogo.agenda(15,"jwt-usuario")).thenReturn(agenda(true,false,List.of(regla(LocalTime.NOON,LocalTime.of(14,0),30,true))));
        assertThat(servicio.consultar(15,fecha,"jwt-usuario").turnos()).isEmpty();
        verifyNoInteractions(catedra);
    }
    @Test void excluyeReglasDeshabilitadasYOtroDia() {
        configurar(List.of(regla(LocalTime.NOON,LocalTime.of(14,0),30,false),
            new Horario(2L,15L,DayOfWeek.TUESDAY,LocalTime.NOON,LocalTime.of(14,0),30,true)),List.of());
        assertThat(servicio.consultar(15,fecha,"jwt-usuario").turnos()).isEmpty();
        verifyNoInteractions(catedra);
    }
    @Test void fechasFueraDeRangoNoLlamanDependencias() {
        assertThatThrownBy(()->servicio.consultar(15,LocalDate.of(2026,11,5),"jwt")).isInstanceOf(ErrorTurnos.class);
        assertThatThrownBy(()->servicio.consultar(15,LocalDate.of(2027,3,1),"jwt")).isInstanceOf(ErrorTurnos.class);
        verifyNoInteractions(catalogo,catedra);
    }
    @Test void rechazaDuracionCeroSinEntrarEnBucle() {
        configurar(List.of(regla(LocalTime.NOON,LocalTime.of(14,0),0,true)),List.of());
        assertThatThrownBy(()->servicio.consultar(15,fecha,"jwt-usuario")).isInstanceOf(ErrorTurnos.class);
        verifyNoInteractions(catedra);
    }
    @Test void noTrataUnaRespuestaIncompletaComoAgendaLibre() {
        configurar(List.of(regla(LocalTime.NOON,LocalTime.of(14,0),30,true)),List.of());
        when(catedra.consultar(15,fecha)).thenReturn(new Ocupaciones(15L,fecha,fecha,null));
        assertThatThrownBy(()->servicio.consultar(15,fecha,"jwt-usuario")).isInstanceOf(ErrorTurnos.class);
    }
    @Test void rechazaOcupacionesDeOtroProfesional() {
        configurar(List.of(regla(LocalTime.NOON,LocalTime.of(14,0),30,true)),List.of());
        when(catedra.consultar(15,fecha)).thenReturn(new Ocupaciones(99L,fecha,fecha,List.of()));
        assertThatThrownBy(()->servicio.consultar(15,fecha,"jwt-usuario")).isInstanceOf(ErrorTurnos.class);
    }
    @Test void excluyePasadosEnLaZonaDeAtencion() {
        var hoy=LocalDate.of(2026,11,6);
        var h=new Horario(1L,15L,DayOfWeek.FRIDAY,LocalTime.of(8,0),LocalTime.of(10,0),30,true);
        when(catalogo.agenda(15,"jwt-usuario")).thenReturn(agenda(true,true,List.of(h)));
        when(catedra.consultar(15,hoy)).thenReturn(new Ocupaciones(15L,hoy,hoy,List.of()));
        assertThat(servicio.consultar(15,hoy,"jwt-usuario").turnos()).extracting(DisponibilidadServicio.Turno::horaInicio)
            .containsExactly(LocalTime.of(9,30));
    }
    @Test void contratosInconsistentesNoProducenDisponibilidad() {
        configurar(List.of(regla(LocalTime.NOON,LocalTime.of(14,0),30,true),regla(LocalTime.NOON,LocalTime.of(14,0),60,true)),List.of());
        assertThatThrownBy(()->servicio.consultar(15,fecha,"jwt-usuario")).isInstanceOf(ErrorTurnos.class);
    }
}
