package ar.com.modarelli.turnos.config;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
@Configuration
public class TiempoConfiguration {
    @Bean Clock reloj(@Value("${turnos.zona}") String zona) { return Clock.system(ZoneId.of(zona)); }
}
