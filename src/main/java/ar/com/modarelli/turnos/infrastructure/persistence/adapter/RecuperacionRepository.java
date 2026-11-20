package ar.com.modarelli.turnos.infrastructure.persistence.adapter;

import ar.com.modarelli.turnos.domain.ports.out.RecuperacionPort;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class RecuperacionRepository implements RecuperacionPort {
    private final JdbcTemplate jdbc;

    private final ar.com.modarelli.turnos.infrastructure.config.LimitesRecuperacion limites;

    public RecuperacionRepository(
            JdbcTemplate jdbc,
            ar.com.modarelli.turnos.infrastructure.config.LimitesRecuperacion limites) {
        this.jdbc = jdbc;
        this.limites = limites;
    }

    @Transactional
    public List<Pendiente> pendientes(Instant ahora) {
        jdbc.update(
                "update proceso_reserva set"
                    + " estado='RECHAZADO',error_code='VALIDACION_INTERRUMPIDA',error_http=503,actualizado_en=?"
                    + " where estado='CREADO' and actualizado_en<=?",
                Timestamp.from(ahora),
                Timestamp.from(ahora.minusSeconds(30)));
        jdbc.update(
                "update proceso_reserva set"
                    + " estado='RESULTADO_DESCONOCIDO',error_code='RESULTADO_HOLD_DESCONOCIDO',error_http=503"
                    + " where estado='SOLICITANDO' and actualizado_en<=?",
                Timestamp.from(ahora.minusSeconds(30)));
        jdbc.update(
                "update proceso_reserva set"
                    + " estado='CONFIRMACION_DESCONOCIDA',error_code='RESULTADO_CONFIRMACION_DESCONOCIDO',error_http=503"
                    + " where estado='CONFIRMANDO' and actualizado_en<=?",
                Timestamp.from(ahora.minusSeconds(30)));
        jdbc.update(
                "update proceso_reserva set"
                    + " estado='CANCELACION_DESCONOCIDA',error_code='RESULTADO_CANCELACION_DESCONOCIDO',error_http=503"
                    + " where estado='CANCELANDO' and actualizado_en<=?",
                Timestamp.from(ahora.minusSeconds(30)));
        jdbc.update(
                "update proceso_reserva set estado='VENCIDO',actualizado_en=? where estado in"
                        + " ('BLOQUEADO','CONFIRMACION_RECHAZADA') and vence_en<=?",
                Timestamp.from(ahora),
                Timestamp.from(ahora));
        jdbc.update(
                "update proceso_reserva set requiere_revision=true where recuperacion_intentos>=?"
                    + " and recuperacion_proxima<=? and estado in"
                    + " ('RESULTADO_DESCONOCIDO','CONFIRMACION_DESCONOCIDA','ESPERANDO_TELEFONO','TELEFONO_ENVIADO','CANCELACION_DESCONOCIDA')",
                limites.maxIntentos,
                Timestamp.from(ahora));
        return jdbc.query(
                """
select p.id,p.usuario_id,u.public_id,p.reservation_process_id from proceso_reserva p
join app_user u on u.id=p.usuario_id
where p.estado in ('SOLICITANDO','RESULTADO_DESCONOCIDO','CONFIRMANDO',
'CONFIRMACION_DESCONOCIDA','ESPERANDO_TELEFONO','TELEFONO_ENVIADO',
'CANCELANDO','CANCELACION_DESCONOCIDA')
and not p.requiere_revision and p.actualizado_en < ?
and (p.recuperacion_proxima is null or p.recuperacion_proxima <= ?)
order by p.recuperacion_en nulls first,p.creado_en,p.id limit 20
""",
                (rs, n) ->
                        new Pendiente(
                                rs.getObject(1, UUID.class),
                                rs.getLong(2),
                                rs.getString(3),
                                rs.getString(4)),
                Timestamp.from(ahora.minusSeconds(30)),
                Timestamp.from(ahora));
    }

    public boolean abandonar(UUID id, long usuario, Instant ahora) {
        return jdbc.update(
                        """
update proceso_reserva set estado='ABANDONADO_LOCAL',actualizado_en=?,
recuperacion_resultado='ABANDONO_LOCAL',requiere_revision=false,recuperacion_proxima=null
where id=? and usuario_id=? and
((estado='RESULTADO_DESCONOCIDO' and requiere_revision and hold_id is null and reservation_process_id is null
and reserva_id is null and solicitud_telefono_id is null and confirmacion_iniciada_en is null)
or estado='ABANDONADO_LOCAL')
""",
                        Timestamp.from(ahora),
                        id,
                        usuario)
                == 1;
    }

    public boolean iniciar(UUID id, Instant ahora) {
        return jdbc.update(
                        """
update proceso_reserva set recuperacion_intentos=recuperacion_intentos+1,
recuperacion_en=?, recuperacion_resultado='EN_CURSO',
recuperacion_proxima=cast(? as timestamptz) + make_interval(secs => least(3600.0, ? * power(2.0, least(recuperacion_intentos, 10))))
where id=? and not requiere_revision and recuperacion_intentos < ?
and (recuperacion_proxima is null or recuperacion_proxima <= ?)
and estado in ('RESULTADO_DESCONOCIDO','CONFIRMACION_DESCONOCIDA','ESPERANDO_TELEFONO',
'TELEFONO_ENVIADO','CANCELACION_DESCONOCIDA')
""",
                        Timestamp.from(ahora),
                        Timestamp.from(ahora),
                        limites.esperaSegundos,
                        id,
                        limites.maxIntentos,
                        Timestamp.from(ahora))
                == 1;
    }

    public void registrar(UUID id, Instant ahora, String resultado) {
        jdbc.update(
                """
                update proceso_reserva set recuperacion_en=?,recuperacion_resultado=?,
                requiere_revision=(estado in ('RESULTADO_DESCONOCIDO','CONFIRMACION_DESCONOCIDA',
                'ESPERANDO_TELEFONO','TELEFONO_ENVIADO','CANCELACION_DESCONOCIDA')
                and (recuperacion_intentos >= ? or reservation_process_id is null)) where id=?
                """,
                Timestamp.from(ahora),
                resultado,
                limites.maxIntentos,
                id);
    }
}
