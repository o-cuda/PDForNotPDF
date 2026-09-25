package io.github.ocuda.pdfornotpdf.service;

/**
 * Stati del ciclo di vita di una versione pubblicata (E-Proposte/04 / R4: una sola fonte).
 *
 * I valori sono la serializzazione su index.json e la convenzione condivisa col frontend
 * (workspace.html li confronta come stringhe): cambiarli richiede una migrazione.
 */
public enum ReleaseStatus {

    /** Candidate in attesa di approvazione (o merge PR sul remote). */
    CANDIDATE("candidate"),

    /** Approvata in-app, con data di efficacia. */
    APPROVED("approved"),

    /** Arrivata su main via PR merge (sync dal remote). */
    PUBLISHED("published"),

    /** Scartata durante la revisione. */
    REJECTED("rejected");

    private final String value;

    ReleaseStatus(String value) {
        this.value = value;
    }

    /** Valore serializzato su index.json (contratto anche col frontend). */
    public String value() {
        return value;
    }

    /** true se il valore grezzo letto da index.json corrisponde a questo stato. */
    public boolean matches(String raw) {
        return value.equals(raw);
    }
}
