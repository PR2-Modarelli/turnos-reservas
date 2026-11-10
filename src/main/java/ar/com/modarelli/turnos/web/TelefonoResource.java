package ar.com.modarelli.turnos.web;
import ar.com.modarelli.turnos.kafka.FlujoKafkaServicio;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/turnos/procesos")
public class TelefonoResource {
 private final FlujoKafkaServicio servicio;
 public TelefonoResource(FlujoKafkaServicio servicio){this.servicio=servicio;}
 public record Telefono(@NotBlank @Size(max=100) String telefono) { @Override public String toString(){return "Telefono[oculto]";} }
 @GetMapping("/{id}/informacion")
 public Map<String,Object> informacion(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return servicio.informacion(id,jwt);}
 @PostMapping("/{id}/telefono")
 public ResponseEntity<Map<String,Object>> telefono(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID clave,
  @Valid @RequestBody Telefono body,@AuthenticationPrincipal Jwt jwt) {
  return ResponseEntity.accepted().body(servicio.telefono(id,clave,body.telefono(),jwt));
 }
}
