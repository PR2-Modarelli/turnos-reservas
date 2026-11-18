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

    public RecuperacionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        return jdbc.query(
                """
select p.id,p.usuario_id,u.public_id,p.reservation_process_id from proceso_reserva p
join app_user u on u.id=p.usuario_id
where p.estado in ('SOLICITANDO','RESULTADO_DESCONOCIDO','CONFIRMANDO',
'CONFIRMACION_DESCONOCIDA','ESPERANDO_TELEFONO','TELEFONO_ENVIADO',
'CANCELANDO','CANCELACION_DESCONOCIDA')
and p.actualizado_en < ? and (p.recuperacion_en is null or p.recuperacion_en < ?)
order by p.recuperacion_en nulls first,p.creado_en,p.id limit 20
""",
                (rs, n) ->
                        new Pendiente(
                                rs.getObject(1, UUID.class),
                                rs.getLong(2),
                                rs.getString(3),
                                rs.getString(4)),
                Timestamp.from(ahora.minusSeconds(30)),
                Timestamp.from(ahora.minusSeconds(60)));
    }

    public void registrar(UUID id, Instant ahora, String resultado) {
        jdbc.update(
                "update proceso_reserva set recuperacion_en=?,recuperacion_resultado=? where id=?",
                Timestamp.from(ahora),
                resultado,
                id);
    }
}
