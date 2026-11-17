package ar.com.modarelli.turnos.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

import java.time.*;

@Configuration
public class TiempoConfiguration {
    @Bean
    Clock reloj(@Value("${turnos.zona}") String zona) {
        return Clock.system(ZoneId.of(zona));
    }
}
