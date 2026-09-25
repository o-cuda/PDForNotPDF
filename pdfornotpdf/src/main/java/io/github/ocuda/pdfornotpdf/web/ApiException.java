package io.github.ocuda.pdfornotpdf.web;

/**
 * Base delle eccezioni applicative con stato HTTP (E-Proposte/04 / R2).
 * I service lanciano, il GlobalExceptionHandler mappa su risposta.
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
