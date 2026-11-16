package ar.com.modarelli.turnos.domain.ports.out;

public interface PublicarTelefonoPort {
    void publicar(String proceso, String contenido) throws InterruptedException;
}
