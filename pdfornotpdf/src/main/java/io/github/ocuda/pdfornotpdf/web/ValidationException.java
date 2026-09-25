package io.github.ocuda.pdfornotpdf.web;

/** Richiesta non valida (percorsi, parametri, payload) → 400. */
public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super(400, message);
    }
}
