package ar.com.modarelli.turnos.infrastructure.web.controller;

import ar.com.modarelli.turnos.domain.exception.ErrorTurnos;
import ar.com.modarelli.turnos.domain.exception.EstadoOperacion;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErroresResource {
    @ExceptionHandler(ErrorTurnos.class)
    ProblemDetail manejar(ErrorTurnos error) {
        ProblemDetail problema =
                ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.valueOf(error.status().value()),
                        error.getMessage());
        problema.setProperty("code", error.code());
        if (error instanceof ar.com.modarelli.turnos.domain.exception.ErrorBloqueo bloqueo
                && bloqueo.procesoId() != null)
            problema.setProperty("procesoId", bloqueo.procesoId());
        return problema;
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ProblemDetail validar(org.springframework.web.bind.MethodArgumentNotValidException error) {
        ProblemDetail problema =
                ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.valueOf(
                                EstadoOperacion.BAD_REQUEST.value()),
                        "Datos de entrada invalidos");
        problema.setProperty("code", "VALIDATION_ERROR");
        problema.setProperty(
                "fieldErrors",
                error.getBindingResult().getFieldErrors().stream()
                        .map(
                                e ->
                                        java.util.Map.of(
                                                "field", e.getField(), "message", "Valor invalido"))
                        .toList());
        return problema;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail jsonInvalido() {
        ProblemDetail problema =
                ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.valueOf(
                                EstadoOperacion.BAD_REQUEST.value()),
                        "JSON invalido o campos no admitidos");
        problema.setProperty("code", "VALIDATION_ERROR");
        return problema;
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ProblemDetail baseNoDisponible() {
        ProblemDetail problema =
                ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.valueOf(
                                EstadoOperacion.SERVICE_UNAVAILABLE.value()),
                        "No se pudo acceder a los datos de turnos");
        problema.setProperty("code", "PERSISTENCIA_NO_DISPONIBLE");
        return problema;
    }

    @ExceptionHandler({
        org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ProblemDetail parametrosInvalidos() {
        ProblemDetail problema =
                ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.valueOf(
                                EstadoOperacion.BAD_REQUEST.value()),
                        "Parametro o cabecera obligatoria ausente o invalida");
        problema.setProperty("code", "VALIDATION_ERROR");
        return problema;
    }
}
