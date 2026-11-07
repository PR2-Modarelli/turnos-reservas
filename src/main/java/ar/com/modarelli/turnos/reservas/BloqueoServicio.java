package ar.com.modarelli.turnos.reservas;

import ar.com.modarelli.turnos.auth.CuentaServicio;
import ar.com.modarelli.turnos.integration.*;
import ar.com.modarelli.turnos.integration.HoldGateway.*;
import ar.com.modarelli.turnos.reservas.BloqueoDtos.*;
import ar.com.modarelli.turnos.service.*;
import java.time.*;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class BloqueoServicio {
    private final CuentaServicio cuentas;
    private final ProcesoReservaRepository procesos;
    private final CatalogoGateway catalogo;
    private final HoldGateway catedra;
    private final Clock reloj;
    public BloqueoServicio(CuentaServicio cuentas,ProcesoReservaRepository procesos,CatalogoGateway catalogo,HoldGateway catedra,Clock reloj) {
        this.cuentas=cuentas;this.procesos=procesos;this.catalogo=catalogo;this.catedra=catedra;this.reloj=reloj;
    }
    public Resultado crear(UUID clave,Solicitud solicitud,Jwt jwt) {
        var cuenta=cuentas.cuenta(jwt);
        if(solicitud.horaInicio().getNano()!=0)
            throw new ErrorTurnos(HttpStatus.BAD_REQUEST,"INVALID_SLOT","La hora no admite fracciones de segundo");
        Inicio inicio=procesos.iniciar(cuenta.id(),clave,solicitud,reloj.instant());
        Proceso proceso=inicio.proceso();
        if(!inicio.nuevo())return resultado(proceso,false);
        try {
            LocalDate hoy=LocalDate.now(reloj);
            if(solicitud.fecha().isBefore(hoy) || solicitud.fecha().isAfter(hoy.plusDays(90)) ||
                !LocalDateTime.of(solicitud.fecha(),solicitud.horaInicio()).isAfter(LocalDateTime.now(reloj)))
                throw new ErrorTurnos(HttpStatus.BAD_REQUEST,"INVALID_SLOT","Fecha u horario invalido");
            var agenda=catalogo.agenda(solicitud.profesionalId(),jwt.getTokenValue());
            var candidatos=ReglasAgenda.candidatos(agenda,solicitud.profesionalId(),solicitud.fecha());
            if(!agenda.profesional().habilitado() || !agenda.categoriaHabilitada())
                throw new ErrorTurnos(HttpStatus.BAD_REQUEST,"PROFESSIONAL_DISABLED","Profesional o categoria deshabilitado");
            if(!candidatos.containsKey(solicitud.horaInicio()))
                throw new ErrorTurnos(HttpStatus.BAD_REQUEST,"INVALID_SLOT","Horario fuera de agenda");
            catedra.exigirConfiguracionHold();
        } catch(ErrorTurnos error) {
            procesos.rechazar(proceso.id(),error.code(),error.status().value(),reloj.instant());
            throw new ErrorBloqueo(error.status(),error.code(),error.getMessage(),proceso.id());
        }
        if(!procesos.marcarEnvio(proceso.id(),reloj.instant()))
            return resultado(procesos.consultar(proceso.id(),cuenta.id(),reloj.instant()),false);
        // Fuera de cualquier transaccion de base. No hay reintento automatico del POST.
        HoldRespuesta hold;
        try {
            hold=catedra.crearHold(new HoldSolicitud(solicitud.profesionalId(),solicitud.fecha(),solicitud.horaInicio(),cuenta.publicId().toString()));
        } catch(ErrorHoldRemoto error) {
            if(error.incierto())procesos.desconocido(proceso.id(),reloj.instant());
            else procesos.rechazar(proceso.id(),error.code(),error.status().value(),reloj.instant());
            throw new ErrorBloqueo(error.status(),error.code(),"No se pudo completar la solicitud de bloqueo",proceso.id());
        }
        try {
            procesos.guardarHold(proceso.id(),hold.holdId(),hold.reservationProcessId(),hold.expiresAt(),reloj.instant());
        } catch(DataAccessException error) {
            // El intento SOLICITANDO ya quedo durable. Si cae la base no se pierde el rastro ni se reenvia el POST.
            throw new ErrorBloqueo(HttpStatus.SERVICE_UNAVAILABLE,"RESULTADO_HOLD_DESCONOCIDO",
                "No se pudo conservar el resultado; consultar el proceso antes de continuar",proceso.id());
        }
        return resultado(procesos.consultar(proceso.id(),cuenta.id(),reloj.instant()),true);
    }
    public Vista consultar(UUID id,Jwt jwt) {
        var cuenta=cuentas.cuenta(jwt);
        return procesos.consultar(id,cuenta.id(),reloj.instant()).vista();
    }
    private Resultado resultado(Proceso proceso,boolean creado) {
        if(proceso.estado()==Estado.RECHAZADO)
            throw new ErrorBloqueo(HttpStatus.valueOf(proceso.errorHttp()),proceso.errorCode(),"La solicitud de bloqueo fue rechazada",proceso.id());
        return new Resultado(proceso.vista(),creado);
    }
}
