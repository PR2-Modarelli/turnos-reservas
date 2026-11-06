package ar.com.modarelli.turnos.web;
import ar.com.modarelli.turnos.service.ErrorTurnos;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class ErroresResource {
    @ExceptionHandler(ErrorTurnos.class)
    ProblemDetail manejar(ErrorTurnos error) {
        ProblemDetail problema=ProblemDetail.forStatusAndDetail(error.status(),error.getMessage());
        problema.setProperty("code",error.code());
        return problema;
    }
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ProblemDetail validar(org.springframework.web.bind.MethodArgumentNotValidException error) {
        ProblemDetail problema=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Datos de entrada invalidos");
        problema.setProperty("code","VALIDATION_ERROR");
        problema.setProperty("fieldErrors",error.getBindingResult().getFieldErrors().stream()
            .map(e->java.util.Map.of("field",e.getField(),"message","Valor invalido")).toList());
        return problema;
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail jsonInvalido() {
        ProblemDetail problema=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"JSON invalido o campos no admitidos");
        problema.setProperty("code","VALIDATION_ERROR");return problema;
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ProblemDetail baseNoDisponible() {
        ProblemDetail problema=ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,"No se pudo acceder a los datos de turnos");
        problema.setProperty("code","PERSISTENCIA_NO_DISPONIBLE");return problema;
    }
}
