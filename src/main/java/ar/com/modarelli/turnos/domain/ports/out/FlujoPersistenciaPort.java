package ar.com.modarelli.turnos.domain.ports.out;

import ar.com.modarelli.turnos.domain.model.ProcesoFlujo;

import java.time.*;
import java.util.*;

public interface FlujoPersistenciaPort {
    int registrarEntrada(String p0, String p1, String p2, Instant p3);

    int marcarIntento(Instant p0, String p1);

    int marcarProcesado(String p0, String p1);

    int guardarCancelacion(Long p0, Instant p1, String p2, Instant p3, UUID p4);

    int cancelarSalidas(UUID p0);

    int guardarSolicitud(String p0, Instant p1, Instant p2, Instant p3, UUID p4);

    int guardarConfirmacion(Long p0, Instant p1, UUID p2);

    int guardarRechazo(Instant p0, String p1, Instant p2, UUID p3);

    int guardarResultado(String p0, String p1, Instant p2, UUID p3);

    int encolarTelefono(UUID p0, UUID p1, UUID p2, String p3, String p4, Instant p5);

    int marcarTelefono(Instant p0, UUID p1);

    List<String> entradaPendiente(String id);

    List<ProcesoFlujo> porCorrelacion(String proceso, String hold);

    List<ProcesoFlujo> propio(UUID id, long usuario);

    List<String> telefonos(UUID id, UUID clave);

    Map<String, Object> recuperacion(UUID id);

    List<String> entradasPendientes();

    void cancelarVencidos(Instant ahora);

    List<EnvioTelefono> salidasPendientes();

    void publicado(UUID evento, Instant ahora);

    void cuarentena(String topic, int particion, long posicion, String payload, Instant ahora);

    record EnvioTelefono(UUID evento, String proceso, String payload) {
        @Override
        public String toString() {
            return "EnvioTelefono[contenido oculto]";
        }
    }
}
