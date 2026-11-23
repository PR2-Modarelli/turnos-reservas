package ar.com.modarelli.turnos.infrastructure.persistence.adapter;

import ar.com.modarelli.turnos.domain.model.CuentaDtos.*;
import ar.com.modarelli.turnos.domain.ports.out.UsuarioPort;
import ar.com.modarelli.turnos.domain.ports.out.UsuarioPort.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Repository
public class UsuarioRepository implements UsuarioPort {
    private final JdbcTemplate jdbc;

    public UsuarioRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Usuario> porLogin(String login) {
        return jdbc.query("select * from app_user where login=?", this::mapear, login).stream()
                .findFirst();
    }

    public Optional<Usuario> porIdPublico(UUID id) {
        return jdbc.query("select * from app_user where public_id=?", this::mapear, id).stream()
                .findFirst();
    }

    public Optional<Cuenta> actualizarNombres(
            UUID id, String login, String nombre, String apellido) {
        return jdbc
                .query(
                        """
                        update app_user set first_name=?,last_name=?
                        where public_id=? and login=? and activated=true returning *
                        """,
                        this::mapear,
                        nombre,
                        apellido,
                        id,
                        login)
                .stream()
                .findFirst()
                .map(Usuario::cuenta);
    }

    public boolean existeEmail(String email) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from app_user where email=?)",
                        Boolean.class,
                        email));
    }

    public Cuenta crear(Registro datos, String login, String email, String hash, Instant ahora) {
        UUID publicId = UUID.randomUUID();
        try {
            return jdbc.queryForObject(
                            """
insert into app_user(public_id,login,password_hash,first_name,last_name,email,image_url,lang_key,activated,created_at)
values(?,?,?,?,?,?,?,?,true,?) returning *
""",
                            this::mapear,
                            publicId,
                            login,
                            hash,
                            datos.firstName(),
                            datos.lastName(),
                            email,
                            datos.imageUrl(),
                            datos.langKey() == null ? "es" : datos.langKey(),
                            java.sql.Timestamp.from(ahora))
                    .cuenta();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ar.com.modarelli.turnos.domain.exception.ConflictoCuenta(e);
        }
    }

    private Usuario mapear(ResultSet fila, int indice) throws SQLException {
        return new Usuario(
                new Cuenta(
                        fila.getLong("id"),
                        fila.getObject("public_id", UUID.class),
                        fila.getString("login"),
                        fila.getString("first_name"),
                        fila.getString("last_name"),
                        fila.getString("email"),
                        fila.getString("image_url"),
                        fila.getString("lang_key"),
                        fila.getBoolean("activated"),
                        List.of("ROLE_USER"),
                        fila.getTimestamp("created_at").toInstant()),
                fila.getString("password_hash"));
    }
}
