package io.github.ocuda.pdfornotpdf.web;

/** Risorsa richiesta inesistente (es. file/versione non trovata) → 404. */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(404, message);
    }
}
