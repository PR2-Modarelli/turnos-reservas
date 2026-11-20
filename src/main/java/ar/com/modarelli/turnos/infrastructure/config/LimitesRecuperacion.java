package ar.com.modarelli.turnos.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LimitesRecuperacion {
    public final int maxIntentos;
    public final long esperaSegundos;

    public LimitesRecuperacion(
            @Value("${turnos.recuperacion.max-intentos:6}") int maxIntentos,
            @Value("${turnos.recuperacion.espera-segundos:60}") long esperaSegundos) {
        if (maxIntentos < 1 || maxIntentos > 100 || esperaSegundos < 1 || esperaSegundos > 3600)
            throw new IllegalArgumentException("Limites de recuperacion invalidos");
        this.maxIntentos = maxIntentos;
        this.esperaSegundos = esperaSegundos;
    }
}
