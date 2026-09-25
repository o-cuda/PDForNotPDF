package io.github.ocuda.pdfornotpdf.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;

/**
 * Un solo punto per gli errori HTTP di tutti i controller (E-Proposte/04 / R2, con
 * l'audit 03 / R7: gli errori vengono LOGGATI con contesto invece di essere ingoiati).
 *
 * Mapping:
 *   ApiException (Validation/NotFound/Conflict) → 400 / 404 / 409
 *   IllegalArgumentException                    → 400 (validazione dei service)
 *   TemplateInputException                      → 400 (template inesistente o rotto)
 *   FileNotFoundException                       → 404
 *   FileAlreadyExistsException                  → 409
 *   IOException / UncheckedIOException          → 400 (errori IO presentabili all'utente)
 *   parametri/payload malformati                → 400
 *   qualunque altra eccezione                   → 500 (loggata con stacktrace)
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> apiException(ApiException e) {
        return text(e.status(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> illegalArgument(IllegalArgumentException e) {
        return text(400, messageOf(e));
    }

    @ExceptionHandler(org.thymeleaf.exceptions.TemplateInputException.class)
    public ResponseEntity<String> templateInput(org.thymeleaf.exceptions.TemplateInputException e) {
        log.warn("Template non risolvibile: {}", e.getMessage());
        return text(400, "Template non valido o inesistente: " + messageOf(e));
    }

    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<String> notFound(FileNotFoundException e) {
        return text(404, messageOf(e));
    }

    @ExceptionHandler(FileAlreadyExistsException.class)
    public ResponseEntity<String> conflict(FileAlreadyExistsException e) {
        return text(409, "Esiste già: " + e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> io(IOException e) {
        log.warn("Errore I/O: {}", e.toString());
        return text(400, messageOf(e));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<String> uncheckedIo(UncheckedIOException e) {
        log.warn("Errore I/O inatteso: {}", e.getCause() != null ? e.getCause().toString() : e.toString());
        return text(400, "Errore di lettura/scrittura: "
                + (e.getCause() != null ? e.getCause().getMessage() : messageOf(e)));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MultipartException.class})
    public ResponseEntity<String> badRequest(Exception e) {
        return text(400, "Richiesta non valida: " + messageOf(e));
    }

    /** URL senza mapping (es. endpoint non implementato): 404 silenzioso, non 500. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<String> noResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return text(404, "Risorsa non trovata: " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> generic(Exception e) {
        log.error("Errore inatteso", e);
        return text(500, "Errore inatteso: " + messageOf(e));
    }

    public static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static ResponseEntity<String> text(int status, String message) {
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(message);
    }
}
