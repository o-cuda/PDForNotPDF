package io.github.ocuda.pdfornotpdf.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unico punto di validazione e risoluzione dei percorsi del workspace (audit 05 / K1).
 *
 * Le falle di classe S1 (C1, C2, B5) nascevano dai punti che NON passavano per una guardia:
 * da qui in poi ogni lettura/scrittura di file del workspace usa QUESTA classe:
 *  - {@link #validateRelPath}  — segmenti non vuoti, senza "."/"..", niente nascosti
 *  - {@link #staysInside}      — true se il relativo normalizzato resta nella radice
 *  - {@link #resolveInside}    — risolve con confine verificato (lancia 400 via advice)
 *  - {@link #readTextInside}   — lettura testuale protetta (C1)
 *  - {@link #readTextOrNull} / {@link #readBytesOrNull} — letture silenziose per il render
 *    (fuori dal workspace o assente → null, mai contenuto esterno)
 *
 * Le eccezioni sono IllegalArgumentException (→ 400 dal GlobalExceptionHandler) e
 * IOException per I/O reale.
 */
public final class WorkspacePaths {

    private WorkspacePaths() {
    }

    /**
     * Valida un percorso relativo: segmenti non vuoti, senza punti-inizi (niente nascosti/.git),
     * lunghezza ragionevole.
     *
     * @throws IllegalArgumentException con messaggio presentabile all'utente
     */
    public static void validateRelPath(String relPath) {
        if (relPath == null || relPath.isBlank()) throw new IllegalArgumentException("Percorso vuoto");
        String p = relPath.replace('\\', '/');
        if (p.length() > 1024) throw new IllegalArgumentException("Percorso non valido: " + relPath);
        String[] segments = p.split("/");
        for (String s : segments) {
            if (s.isBlank() || s.equals(".") || s.equals("..") || s.startsWith(".")) {
                throw new IllegalArgumentException("Nome non valido: '" + s + "'");
            }
        }
        if (segments[segments.length - 1].length() > 255) {
            throw new IllegalArgumentException("Nome troppo lungo (max 255 caratteri)");
        }
    }

    /**
     * true se un percorso relativo, una volta normalizzato, resta dentro la radice:
     * scarta i riferimenti con "../" che fuggono dal workspace (path escape nel render).
     */
    public static boolean staysInside(String relPath) {
        if (relPath == null || relPath.isBlank()) return false;
        Path p = Path.of(relPath.replace('\\', '/')).normalize();
        if (p.isAbsolute()) return false;
        for (Path segment : p) {
            if (segment.toString().equals("..")) return false;
        }
        return true;
    }

    /**
     * Risolve un percorso relativo dentro root con normalizzazione e verifica del confine.
     *
     * @throws IllegalArgumentException se il riferimento esce dal workspace
     */
    public static Path resolveInside(Path root, String relPath) {
        Path r = root.toAbsolutePath().normalize();
        Path t = r.resolve(relPath.replace('\\', '/')).normalize();
        if (!t.startsWith(r)) {
            throw new IllegalArgumentException("Percorso fuori dal workspace: " + relPath);
        }
        return t;
    }

    /** Come {@link #resolveInside} ma restituisce null invece di lanciare (per il render). */
    public static Path resolveInsideOrNull(Path root, String relPath) {
        Path r = root.toAbsolutePath().normalize();
        Path t = r.resolve(relPath.replace('\\', '/')).normalize();
        return t.startsWith(r) ? t : null;
    }

    /** Lettura testuale protetta: lancia se il percorso esce dal workspace o il file manca. */
    public static String readTextInside(Path root, String relPath) throws IOException {
        return Files.readString(resolveInside(root, relPath));
    }

    /** Lettura testuale silenziosa per il render: fuori dal workspace o assente → null (mai contenuto esterno). */
    public static String readTextOrNull(Path root, String relPath) {
        Path p = resolveInsideOrNull(root, relPath);
        if (p == null || !Files.isRegularFile(p)) return null;
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return null;
        }
    }

    /** Lettura binaria silenziosa per il render: fuori dal workspace o assente → null. */
    public static byte[] readBytesOrNull(Path root, String relPath) {
        Path p = resolveInsideOrNull(root, relPath);
        if (p == null || !Files.isRegularFile(p)) return null;
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return null;
        }
    }

    /** Percorsi relativi alla radice del workspace; una "/" iniziale è facoltativa e ignorata. */
    public static String normalizeRel(String href) {
        String rel = href.replace('\\', '/');
        while (rel.startsWith("/") || rel.startsWith("./")) {
            rel = rel.startsWith("/") ? rel.substring(1) : rel.substring(2);
        }
        return rel;
    }

    /** Riferimenti esterni (http/https/data/protocol-relative): non vanno mai risolti su disco. */
    public static boolean isExternal(String url) {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:") || url.startsWith("//");
    }

    /** SHA-256 esadecimale (64 char) — usato da manifest release e dal save ottimistico (P1). */
    public static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
