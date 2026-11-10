package ar.com.modarelli.turnos.reservas;

import ar.com.modarelli.turnos.reservas.BloqueoDtos.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProcesoReservaRepository {
    private final JdbcTemplate jdbc;
    public ProcesoReservaRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional
    public Inicio iniciar(long usuario,UUID clave,Solicitud s,Instant ahora) {
        // Serializar solo la preparacion de operaciones del mismo usuario, nunca llamadas HTTP.
        jdbc.queryForObject("select id from app_user where id=? for update",Long.class,usuario);
        refrescar(usuario,ahora);
        var anterior=jdbc.query("select * from proceso_reserva where usuario_id=? and idempotency_key=?",this::mapear,usuario,clave);
        if(!anterior.isEmpty()) {
            Proceso p=anterior.getFirst();
            if(p.profesionalId()!=s.profesionalId() || !p.fecha().equals(s.fecha()) || !p.horaInicio().equals(s.horaInicio()))
                throw new ErrorBloqueo(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUTILIZADA","La clave ya identifica otra solicitud",p.id());
            return new Inicio(p,false);
        }
        var activos=jdbc.query("""
            select * from proceso_reserva where usuario_id=? and profesional_id=? and fecha=? and hora_inicio=?
              and estado in ('CREADO','SOLICITANDO','BLOQUEADO','RESULTADO_DESCONOCIDO','CONFIRMANDO','ESPERANDO_TELEFONO','CONFIRMACION_DESCONOCIDA','CONFIRMACION_RECHAZADA','TELEFONO_ENVIADO','CONFIRMADO')
            """,this::mapear,usuario,s.profesionalId(),s.fecha(),s.horaInicio());
        if(!activos.isEmpty())throw new ErrorBloqueo(HttpStatus.CONFLICT,"PROCESO_ACTIVO_EXISTENTE",
            "Ya existe un intento activo para ese turno",activos.getFirst().id());
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into proceso_reserva(id,usuario_id,idempotency_key,profesional_id,fecha,hora_inicio,estado,creado_en,actualizado_en)
              values(?,?,?,?,?,?,'CREADO',?,?)
            """,id,usuario,clave,s.profesionalId(),s.fecha(),s.horaInicio(),Timestamp.from(ahora),Timestamp.from(ahora));
        return new Inicio(buscar(id,usuario),true);
    }
    @Transactional
    public Proceso consultar(UUID id,long usuario,Instant ahora) {
        refrescar(usuario,ahora);
        return buscar(id,usuario);
    }
    public Proceso buscar(UUID id,long usuario) {
        return jdbc.query("select * from proceso_reserva where id=? and usuario_id=?",this::mapear,id,usuario)
            .stream().findFirst().orElseThrow(()->new ErrorBloqueo(HttpStatus.NOT_FOUND,"PROCESO_NO_ENCONTRADO","Proceso inexistente",null));
    }
    public boolean marcarEnvio(UUID id,Instant ahora) {
        return jdbc.update("update proceso_reserva set estado='SOLICITANDO',actualizado_en=? where id=? and estado='CREADO'",
            Timestamp.from(ahora),id)==1;
    }
    public void rechazar(UUID id,String code,int http,Instant ahora) {
        jdbc.update("""
            update proceso_reserva set estado='RECHAZADO',error_code=?,error_http=?,actualizado_en=?
             where id=? and estado in ('CREADO','SOLICITANDO','RESULTADO_DESCONOCIDO')
            """,code,http,Timestamp.from(ahora),id);
    }
    public void desconocido(UUID id,Instant ahora) {
        jdbc.update("""
            update proceso_reserva set estado='RESULTADO_DESCONOCIDO',error_code='RESULTADO_HOLD_DESCONOCIDO',error_http=503,actualizado_en=?
             where id=? and estado='SOLICITANDO'
            """,Timestamp.from(ahora),id);
    }
    public void guardarHold(UUID id,String holdId,String remoto,Instant vence,Instant ahora) {
        jdbc.update("""
            update proceso_reserva set estado=?,hold_id=?,reservation_process_id=?,vence_en=?,error_code=null,error_http=null,actualizado_en=?
             where id=? and estado in ('SOLICITANDO','RESULTADO_DESCONOCIDO')
            """,vence.isAfter(ahora)?"BLOQUEADO":"VENCIDO",holdId,remoto,Timestamp.from(vence),Timestamp.from(ahora),id);
    }
    public record PreparacionConfirmacion(Proceso proceso,boolean enviar) {}
    @Transactional
    public PreparacionConfirmacion prepararConfirmacion(UUID id,long usuario,Instant ahora) {
        var p=jdbc.query("select * from proceso_reserva where id=? and usuario_id=? for update",this::mapear,id,usuario)
            .stream().findFirst().orElseThrow(()->new ErrorBloqueo(HttpStatus.NOT_FOUND,"PROCESO_NO_ENCONTRADO","Proceso inexistente",null));
        if(p.estado()==Estado.BLOQUEADO && !p.expiresAt().isAfter(ahora)) {
            jdbc.update("update proceso_reserva set estado='VENCIDO',actualizado_en=? where id=?",Timestamp.from(ahora),id);
            return new PreparacionConfirmacion(buscar(id,usuario),false);
        }
        if(p.estado()!=Estado.BLOQUEADO)return new PreparacionConfirmacion(p,false);
        jdbc.update("""
            update proceso_reserva set estado='CONFIRMANDO',confirmacion_iniciada_en=?,actualizado_en=?,error_code=null,error_http=null
             where id=?
            """,Timestamp.from(ahora),Timestamp.from(ahora),id);
        return new PreparacionConfirmacion(buscar(id,usuario),true);
    }
    public void guardarConfirmacion(UUID id,Instant vence,Instant ahora) {
        jdbc.update("""
            update proceso_reserva set estado='ESPERANDO_TELEFONO',confirmacion_vence_en=?,actualizado_en=?,error_code=null,error_http=null
             where id=? and estado in ('CONFIRMANDO','CONFIRMACION_DESCONOCIDA')
            """,Timestamp.from(vence),Timestamp.from(ahora),id);
    }
    public void fallarConfirmacion(UUID id,boolean incierta,String code,int http,Instant ahora) {
        jdbc.update("""
            update proceso_reserva set estado=?,error_code=?,error_http=?,actualizado_en=?
             where id=? and estado in ('CONFIRMANDO','CONFIRMACION_DESCONOCIDA')
            """,incierta?"CONFIRMACION_DESCONOCIDA":"CONFIRMACION_RECHAZADA",code,http,Timestamp.from(ahora),id);
    }
    private void refrescar(long usuario,Instant ahora) {
        jdbc.update("""
            update proceso_reserva set estado='VENCIDO',actualizado_en=?
             where usuario_id=? and estado in ('BLOQUEADO','CONFIRMACION_RECHAZADA') and vence_en<=?
            """,Timestamp.from(ahora),usuario,Timestamp.from(ahora));
        jdbc.update("""
            update proceso_reserva set estado='CONFIRMACION_DESCONOCIDA',error_code='RESULTADO_CONFIRMACION_DESCONOCIDO',error_http=503,actualizado_en=?
             where usuario_id=? and estado='CONFIRMANDO' and actualizado_en<=?
            """,Timestamp.from(ahora),usuario,Timestamp.from(ahora.minusSeconds(30)));
        // Un proceso local anterior al envio puede abandonarse con seguridad. Uno ya enviado no puede repetirse.
        jdbc.update("""
            update proceso_reserva set estado='RECHAZADO',error_code='VALIDACION_INTERRUMPIDA',error_http=503,actualizado_en=?
             where usuario_id=? and estado='CREADO' and actualizado_en<=?
            """,Timestamp.from(ahora),usuario,Timestamp.from(ahora.minusSeconds(30)));
        jdbc.update("""
            update proceso_reserva set estado='RESULTADO_DESCONOCIDO',error_code='RESULTADO_HOLD_DESCONOCIDO',error_http=503,actualizado_en=?
             where usuario_id=? and estado='SOLICITANDO' and actualizado_en<=?
            """,Timestamp.from(ahora),usuario,Timestamp.from(ahora.minusSeconds(30)));
    }
    private Proceso mapear(ResultSet f,int n) throws SQLException {
        Timestamp vence=f.getTimestamp("vence_en");
        return new Proceso(f.getObject("id",UUID.class),f.getLong("usuario_id"),f.getObject("idempotency_key",UUID.class),
            f.getLong("profesional_id"),f.getObject("fecha",LocalDate.class),f.getObject("hora_inicio",LocalTime.class),
            Estado.valueOf(f.getString("estado")),f.getString("hold_id"),f.getString("reservation_process_id"),
            vence==null?null:vence.toInstant(),f.getString("error_code"),(Integer)f.getObject("error_http"),
            f.getTimestamp("creado_en").toInstant(),f.getTimestamp("actualizado_en").toInstant(),
            f.getTimestamp("confirmacion_vence_en")==null?null:f.getTimestamp("confirmacion_vence_en").toInstant());
    }
}
