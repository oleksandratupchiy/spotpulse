package com.spotpulse.controller;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        String detail = switch (status.value()) {
            case 400 -> "Invalid request input";
            case 404 -> "Resource not found";
            case 409 -> "Request conflicts with existing data";
            default -> status.is5xxServerError() ? "An unexpected error occurred" : "Request could not be processed";
        };
        if (ex instanceof ResponseStatusException statusException && !status.is5xxServerError()
                && statusException.getReason() != null) {
            detail = statusException.getReason();
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (ex instanceof MethodArgumentNotValidException invalid) {
            List<Map<String, String>> errors = invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> Map.of("field", error.getField(), "message",
                            error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
                    .toList();
            problem.setProperty("errors", errors);
        }
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> validation(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request input");
        problem.setProperty("errors", ex.getConstraintViolations().stream()
                .map(error -> Map.of("field", error.getPropertyPath().toString(), "message", error.getMessage()))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class})
    public ResponseEntity<ProblemDetail> conflict(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Operation conflicts with existing data or a concurrent request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        log.error("Unexpected API error", ex);
        return ResponseEntity.internalServerError().body(ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"));
    }
}
