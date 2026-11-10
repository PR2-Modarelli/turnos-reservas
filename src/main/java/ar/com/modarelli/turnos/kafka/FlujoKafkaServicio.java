package ar.com.modarelli.turnos.kafka;

import ar.com.modarelli.turnos.auth.CuentaServicio;
import ar.com.modarelli.turnos.reservas.*;
import ar.com.modarelli.turnos.service.ErrorTurnos;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class FlujoKafkaServicio {
 private final JdbcTemplate jdbc; private final CuentaServicio cuentas; private final Clock reloj;
 private final JsonMapper json=JsonMapper.builder().build(); private final String grupo; private final boolean habilitado;
 public FlujoKafkaServicio(JdbcTemplate jdbc,CuentaServicio cuentas,Clock reloj,
  @Value("${turnos.kafka.group-id:}") String grupo,@Value("${turnos.kafka.enabled:false}") boolean habilitado) {
  this.jdbc=jdbc;this.cuentas=cuentas;this.reloj=reloj;this.grupo=grupo;this.habilitado=habilitado;
 }
 private static boolean texto(String s){return s!=null && !s.isBlank() && s.length()<=200;}
 private ErrorTurnos error(HttpStatus h,String c,String m){return new ErrorTurnos(h,c,m);}
 // Inbox y transicion en una misma transaccion. El offset solo se confirma despues del commit.
 @Transactional
 public void recibir(String key,String payload) {
  EventoTurnos e;
  try { e=json.readValue(payload,EventoTurnos.class); }
  catch(RuntimeException ex){throw new IllegalArgumentException("Evento de turnos ilegible",ex);}
  if(e.schemaVersion()==null || e.schemaVersion()!=1 || !texto(e.eventId()) || !texto(e.reservationProcessId())
    || !Objects.equals(key,e.reservationProcessId()) || !texto(e.holdId()) || e.occurredAt()==null)
   throw new IllegalArgumentException("Sobre de evento de turnos invalido");
  if(e.groupId()!=null && !grupo.equals(e.groupId()))throw new IllegalArgumentException("Grupo de evento incorrecto");
  int nuevo=jdbc.update("insert into evento_turnos_entrada(event_id,proceso_remoto,payload,recibido_en) values(?,?,?,?) on conflict do nothing",
   e.eventId(),e.reservationProcessId(),payload,Timestamp.from(reloj.instant()));
  if(nuevo==0)return;
  aplicar(e);
 }
 @Transactional
 public void reprocesar(String id) {
  var filas=jdbc.queryForList("select payload from evento_turnos_entrada where event_id=? and not procesado for update",id);
  if(!filas.isEmpty()) {
   jdbc.update("update evento_turnos_entrada set ultimo_intento=? where event_id=?",Timestamp.from(reloj.instant()),id);
   aplicar(json.readValue((String)filas.getFirst().get("payload"),EventoTurnos.class));
  }
 }
 private void terminar(EventoTurnos e,String error) {
  jdbc.update("update evento_turnos_entrada set procesado=true,error=? where event_id=?",error,e.eventId());
 }
 private void aplicar(EventoTurnos e) {
  var filas=jdbc.queryForList("select * from proceso_reserva where reservation_process_id=? and hold_id=? for update",e.reservationProcessId(),e.holdId());
  if(filas.isEmpty())return; // Puede adelantarse al guardado de la respuesta del hold.
  if(filas.size()!=1)throw new IllegalStateException("Correlacion de proceso ambigua");
  var p=filas.getFirst();UUID id=(UUID)p.get("id");String estado=(String)p.get("estado");
  if(Set.of("CONFIRMADO","VENCIDO","PROCESO_INVALIDO").contains(estado)){terminar(e,null);return;}
  String solicitud=(String)p.get("solicitud_telefono_id");
  switch(e.eventType()==null?"":e.eventType()) {
   case "AdditionalInformationRequested" -> {
    if(!"PHONE_NUMBER".equals(e.requiredField()) || e.expiresAt()==null){terminar(e,"SOLICITUD_INVALIDA");return;}
    if(!Set.of("BLOQUEADO","CONFIRMANDO","CONFIRMACION_DESCONOCIDA","ESPERANDO_TELEFONO").contains(estado)){terminar(e,"ESTADO_INCOMPATIBLE");return;}
    // Una solicitud ya correlacionada no se reemplaza por una entrega atrasada.
    if(solicitud!=null){terminar(e,solicitud.equals(e.eventId())?null:"SOLICITUD_DISTINTA");return;}
    jdbc.update("update proceso_reserva set estado='ESPERANDO_TELEFONO',solicitud_telefono_id=?,solicitud_telefono_en=?,confirmacion_vence_en=?,error_code=null,error_http=null,actualizado_en=? where id=?",
     e.eventId(),Timestamp.from(e.occurredAt()),Timestamp.from(e.expiresAt()),Timestamp.from(reloj.instant()),id);
   }
   case "AppointmentConfirmed","AdditionalInformationRejected","AppointmentProcessExpired","AppointmentProcessInvalid" -> {
    if(!grupo.equals(e.groupId()) || !texto(e.requestEventId())){terminar(e,"CORRELACION_INVALIDA");return;}
    if(solicitud==null)return; // Guardar hasta recibir la solicitud, incluso si se altera el orden.
    if(!solicitud.equals(e.requestEventId())){terminar(e,"SOLICITUD_NO_COINCIDE");return;}
    var fechaSolicitud=(Timestamp)p.get("solicitud_telefono_en");
    if(fechaSolicitud!=null && e.occurredAt().isBefore(fechaSolicitud.toInstant())){terminar(e,"EVENTO_ANTERIOR");return;}
    if("AppointmentConfirmed".equals(e.eventType())) {
     if(e.reservationId()==null || e.reservationId()<=0 || !Objects.equals(e.professionalId(),((Number)p.get("profesional_id")).longValue())
      || !Objects.equals(e.date(),((java.sql.Date)p.get("fecha")).toLocalDate())
      || !Objects.equals(e.startTime(),((java.sql.Time)p.get("hora_inicio")).toLocalTime()) || !"CONFIRMED".equals(e.status())) {
      terminar(e,"RESERVA_NO_COINCIDE");return;
     }
     jdbc.update("update proceso_reserva set estado='CONFIRMADO',reserva_id=?,error_code=null,error_http=null,actualizado_en=? where id=?",e.reservationId(),Timestamp.from(reloj.instant()),id);
    }else if("AdditionalInformationRejected".equals(e.eventType())) {
     if(e.expiresAt()==null || (!texto(e.reason()) || e.reason().length()>80)){terminar(e,"RECHAZO_INVALIDO");return;}
     // Un rechazo habilita una correccion; nunca se vuelve a publicar el mismo intento.
     jdbc.update("update proceso_reserva set estado='ESPERANDO_TELEFONO',confirmacion_vence_en=?,error_code=?,error_http=null,actualizado_en=? where id=?",
      Timestamp.from(e.expiresAt()),e.reason(),Timestamp.from(reloj.instant()),id);
    }else {
     if((!texto(e.reason()) || e.reason().length()>80)){terminar(e,"RESULTADO_INVALIDO");return;}
     jdbc.update("update proceso_reserva set estado=?,error_code=?,error_http=null,actualizado_en=? where id=?",
      "AppointmentProcessExpired".equals(e.eventType())?"VENCIDO":"PROCESO_INVALIDO",e.reason(),Timestamp.from(reloj.instant()),id);
    }
    jdbc.update("update telefono_salida set cancelado=true where proceso_id=? and publicado_en is null",id);
   }
   default -> {terminar(e,"TIPO_NO_SOPORTADO");return;}
  }
  terminar(e,null);
 }
 @Transactional
 public Map<String,Object> informacion(UUID id,Jwt jwt) {
  long usuario=cuentas.cuenta(jwt).id();var p=propio(id,usuario);
  Map<String,Object> vista=new LinkedHashMap<>();
  vista.put("id",id);vista.put("estado",p.get("estado"));vista.put("requestEventId",p.get("solicitud_telefono_id"));
  vista.put("reservationId",p.get("reserva_id"));vista.put("ultimoError",p.get("error_code"));
  Timestamp vence=(Timestamp)p.get("confirmacion_vence_en");vista.put("expiresAt",vence==null?null:vence.toInstant());
  vista.put("requiereTelefono","ESPERANDO_TELEFONO".equals(p.get("estado")) && p.get("solicitud_telefono_id")!=null && vence!=null && vence.toInstant().isAfter(reloj.instant()));
  return vista;
 }
 private Map<String,Object> propio(UUID id,long usuario) {
  return jdbc.queryForList("select * from proceso_reserva where id=? and usuario_id=? for update",id,usuario).stream().findFirst()
   .orElseThrow(()->error(HttpStatus.NOT_FOUND,"PROCESO_NO_ENCONTRADO","Proceso inexistente"));
 }
 @Transactional
 public Map<String,Object> telefono(UUID id,UUID clave,String telefono,Jwt jwt) {
  long usuario=cuentas.cuenta(jwt).id();var p=propio(id,usuario);
  if(telefono==null || telefono.length()>100)throw error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Telefono invalido");
  String normal=telefono.replaceAll("[ ().-]","");
  if(!normal.matches("\\+?[0-9]{7,15}"))throw error(HttpStatus.BAD_REQUEST,"INVALID_PHONE_NUMBER","Telefono invalido");
  var anterior=jdbc.queryForList("select telefono from telefono_salida where proceso_id=? and clave=?",id,clave);
  if(!anterior.isEmpty()) {
   if(!normal.equals(anterior.getFirst().get("telefono")))throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUTILIZADA","La clave ya identifica otro telefono");
   return informacion(id,jwt);
  }
  if(!habilitado || grupo.isBlank())throw error(HttpStatus.SERVICE_UNAVAILABLE,"KAFKA_NO_CONFIGURADO","Falta configurar el intercambio Kafka");
  Timestamp vence=(Timestamp)p.get("confirmacion_vence_en");
  if(!"ESPERANDO_TELEFONO".equals(p.get("estado")) || p.get("solicitud_telefono_id")==null)
   throw error(HttpStatus.CONFLICT,"TELEFONO_NO_SOLICITADO","El proceso no admite un nuevo envio de telefono");
  if(vence==null || !vence.toInstant().isAfter(reloj.instant()))throw error(HttpStatus.CONFLICT,"PROCESO_VENCIDO","El plazo para enviar telefono vencio");
  UUID evento=UUID.randomUUID();Instant ahora=reloj.instant();
  String payload=json.writeValueAsString(Map.of("eventId",evento.toString(),"eventType","AdditionalInformationSubmitted","schemaVersion",1,
   "groupId",grupo,"reservationProcessId",p.get("reservation_process_id"),"holdId",p.get("hold_id"),
   "requestEventId",p.get("solicitud_telefono_id"),"information",Map.of("phoneNumber",normal),"occurredAt",ahora));
  jdbc.update("insert into telefono_salida(event_id,proceso_id,clave,telefono,payload,creado_en) values(?,?,?,?,?,?)",evento,id,clave,normal,payload,Timestamp.from(ahora));
  jdbc.update("update proceso_reserva set estado='TELEFONO_ENVIADO',error_code=null,error_http=null,actualizado_en=? where id=?",Timestamp.from(ahora),id);
  return informacion(id,jwt);
 }
}
