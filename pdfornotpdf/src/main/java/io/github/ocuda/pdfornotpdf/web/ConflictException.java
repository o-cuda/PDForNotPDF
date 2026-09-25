package io.github.ocuda.pdfornotpdf.web;

/** Richiesta in conflitto con lo stato corrente (es. destinazione già esistente) → 409. */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(409, message);
    }
}
