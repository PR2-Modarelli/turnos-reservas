package ar.com.modarelli.turnos.infrastructure.persistence.adapter;

import ar.com.modarelli.turnos.domain.model.ProcesoFlujo;
import ar.com.modarelli.turnos.domain.ports.out.FlujoPersistenciaPort;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
public class JdbcFlujoAdapter implements FlujoPersistenciaPort {
    private final JdbcTemplate jdbc;

    private final ar.com.modarelli.turnos.infrastructure.config.LimitesRecuperacion limites;
    private final Clock clock;

    public JdbcFlujoAdapter(
            JdbcTemplate jdbc,
            ar.com.modarelli.turnos.infrastructure.config.LimitesRecuperacion limites,
            Clock clock) {
        this.jdbc = jdbc;
        this.limites = limites;
        this.clock = clock;
    }

    private int actualizar(String sql, Object... valores) {
        return jdbc.update(
                sql,
                Arrays.stream(valores)
                        .map(v -> v instanceof Instant i ? Timestamp.from(i) : v)
                        .toArray());
    }

    private Instant instante(ResultSet r, String n) throws SQLException {
        var t = r.getTimestamp(n);
        return t == null ? null : t.toInstant();
    }

    private ProcesoFlujo mapear(ResultSet r, int n) throws SQLException {
        return new ProcesoFlujo(
                r.getObject("id", UUID.class),
                r.getString("estado"),
                r.getLong("profesional_id"),
                r.getObject("fecha", LocalDate.class),
                r.getObject("hora_inicio", LocalTime.class),
                r.getString("solicitud_telefono_id"),
                instante(r, "solicitud_telefono_en"),
                (Long) r.getObject("reserva_id"),
                instante(r, "confirmacion_vence_en"),
                r.getString("error_code"),
                r.getString("reservation_process_id"),
                r.getString("hold_id"));
    }

    public List<String> entradaPendiente(String id) {
        return jdbc.queryForList(
                "select payload from evento_turnos_entrada where event_id=? and not procesado for"
                        + " update",
                String.class,
                id);
    }

    public List<ProcesoFlujo> porCorrelacion(String proceso, String hold) {
        return jdbc.query(
                "select * from proceso_reserva where reservation_process_id=? and hold_id=? for"
                        + " update",
                this::mapear,
                proceso,
                hold);
    }

    public List<ProcesoFlujo> propio(UUID id, long usuario) {
        return jdbc.query(
                "select * from proceso_reserva where id=? and usuario_id=? for update",
                this::mapear,
                id,
                usuario);
    }

    public List<String> telefonos(UUID id, UUID clave) {
        return jdbc.queryForList(
                "select telefono from telefono_salida where proceso_id=? and clave=?",
                String.class,
                id,
                clave);
    }

    public List<String> entradasPendientes() {
        return jdbc.queryForList(
                """
select e.event_id from evento_turnos_entrada e where not e.procesado and
((not e.requiere_revision and (e.proximo_intento is null or e.proximo_intento<=?))
or exists (select 1 from proceso_reserva p where p.reservation_process_id=e.proceso_remoto
and p.actualizado_en > coalesce(e.correlacion_revision, '-infinity'::timestamptz)))
order by coalesce(e.ultimo_intento,e.recibido_en),e.event_id limit 100
""",
                String.class,
                Timestamp.from(clock.instant()));
    }

    public Map<String, Object> recuperacion(UUID id) {
        return jdbc.queryForMap(
                "select recuperacion_intentos as intentos, recuperacion_proxima as proxima,"
                    + " (requiere_revision and estado in"
                    + " ('RESULTADO_DESCONOCIDO','CONFIRMACION_DESCONOCIDA','ESPERANDO_TELEFONO','TELEFONO_ENVIADO','CANCELACION_DESCONOCIDA'))"
                    + " as revision, recuperacion_resultado as resultado from proceso_reserva where"
                    + " id=?",
                id);
    }

    public void cancelarVencidos(Instant ahora) {
        actualizar(
                "update telefono_salida s set cancelado=true from proceso_reserva p where"
                    + " s.proceso_id=p.id and s.publicado_en is null and not s.cancelado and"
                    + " (p.estado in"
                    + " ('CONFIRMADO','VENCIDO','PROCESO_INVALIDO','CANCELADO','CANCELANDO','CANCELACION_DESCONOCIDA')"
                    + " or p.confirmacion_vence_en<=?)",
                ahora);
    }

    public List<EnvioTelefono> salidasPendientes() {
        return jdbc.query(
                "select s.*,p.reservation_process_id from telefono_salida s join proceso_reserva p"
                    + " on p.id=s.proceso_id where s.publicado_en is null and not s.cancelado order"
                    + " by s.creado_en limit 20",
                (r, n) ->
                        new EnvioTelefono(
                                r.getObject("event_id", UUID.class),
                                r.getString("reservation_process_id"),
                                r.getString("payload")));
    }

    public void publicado(UUID evento, Instant ahora) {
        actualizar("update telefono_salida set publicado_en=? where event_id=?", ahora, evento);
    }

    public void cuarentena(
            String topic, int particion, long posicion, String payload, Instant ahora) {
        actualizar(
                "insert into evento_turnos_descartado(topic,particion,posicion,payload,recibido_en)"
                        + " values(?,?,?,?,?) on conflict do nothing",
                topic,
                particion,
                posicion,
                payload,
                ahora);
    }

    public int registrarEntrada(String p0, String p1, String p2, Instant p3) {
        return actualizar(
                "insert into evento_turnos_entrada(event_id,proceso_remoto,payload,recibido_en)"
                        + " values(?,?,?,?) on conflict do nothing",
                p0,
                p1,
                p2,
                p3);
    }

    public int marcarIntento(Instant p0, String p1) {
        return actualizar(
                """
update evento_turnos_entrada e set ultimo_intento=?,intentos=intentos+1,
proximo_intento=cast(? as timestamptz) + make_interval(secs => least(3600.0, ? * power(2.0, least(intentos,10)))),
requiere_revision=(intentos+1>=?),
correlacion_revision=(select max(p.actualizado_en) from proceso_reserva p where p.reservation_process_id=e.proceso_remoto)
where event_id=?
""",
                p0,
                p0,
                limites.esperaSegundos,
                limites.maxIntentos,
                p1);
    }

    public int marcarProcesado(String p0, String p1) {
        return actualizar(
                "update evento_turnos_entrada set procesado=true,requiere_revision=false,error=?"
                        + " where event_id=?",
                p0,
                p1);
    }

    public int guardarCancelacion(Long p0, Instant p1, String p2, Instant p3, UUID p4) {
        return actualizar(
                "update proceso_reserva set"
                    + " requiere_revision=false,estado='CANCELADO',reserva_id=?,cancelado_en=?,motivo_cancelacion=?,error_code=null,error_http=null,actualizado_en=?"
                    + " where id=?",
                p0,
                p1,
                p2,
                p3,
                p4);
    }

    public int cancelarSalidas(UUID p0) {
        return actualizar(
                "update telefono_salida set cancelado=true where proceso_id=? and publicado_en is"
                        + " null",
                p0);
    }

    public int guardarSolicitud(String p0, Instant p1, Instant p2, Instant p3, UUID p4) {
        return actualizar(
                "update proceso_reserva set"
                    + " requiere_revision=false,recuperacion_intentos=0,recuperacion_proxima=null,estado='ESPERANDO_TELEFONO',solicitud_telefono_id=?,solicitud_telefono_en=?,confirmacion_vence_en=?,error_code=null,error_http=null,actualizado_en=?"
                    + " where id=?",
                p0,
                p1,
                p2,
                p3,
                p4);
    }

    public int guardarConfirmacion(Long p0, Instant p1, UUID p2) {
        return actualizar(
                "update proceso_reserva set"
                    + " requiere_revision=false,estado='CONFIRMADO',reserva_id=?,error_code=null,error_http=null,actualizado_en=?"
                    + " where id=?",
                p0,
                p1,
                p2);
    }

    public int guardarRechazo(Instant p0, String p1, Instant p2, UUID p3) {
        return actualizar(
                "update proceso_reserva set"
                    + " estado='ESPERANDO_TELEFONO',confirmacion_vence_en=?,error_code=?,error_http=null,actualizado_en=?"
                    + " where id=?",
                p0,
                p1,
                p2,
                p3);
    }

    public int guardarResultado(String p0, String p1, Instant p2, UUID p3) {
        return actualizar(
                "update proceso_reserva set estado=?,error_code=?,error_http=null,actualizado_en=?"
                        + " where id=?",
                p0,
                p1,
                p2,
                p3);
    }

    public int encolarTelefono(UUID p0, UUID p1, UUID p2, String p3, String p4, Instant p5) {
        return actualizar(
                "insert into telefono_salida(event_id,proceso_id,clave,telefono,payload,creado_en)"
                        + " values(?,?,?,?,?,?)",
                p0,
                p1,
                p2,
                p3,
                p4,
                p5);
    }

    public int marcarTelefono(Instant p0, UUID p1) {
        return actualizar(
                "update proceso_reserva set"
                    + " estado='TELEFONO_ENVIADO',error_code=null,error_http=null,actualizado_en=?"
                    + " where id=?",
                p0,
                p1);
    }
}
