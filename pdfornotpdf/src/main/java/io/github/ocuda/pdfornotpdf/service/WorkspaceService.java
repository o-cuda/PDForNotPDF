package io.github.ocuda.pdfornotpdf.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.FileTemplateResolver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class WorkspaceService {

    /** Directory delle release pubblicate: fuori dall'area di lavoro, con storico git dedicato. */
    public static final String RELEASE_DIR = "release";
    /** Area di lavoro: tutti i percorsi file (template, css, json, asset) sono relativi a questa radice. */
    public static final String SNAPSHOT_DIR = "snapshot";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Estensioni modificabili nell'editor. */
    private static final List<String> EDITABLE = List.of(".html", ".css", ".json");
    /** Estensioni immagine: referenziabili nei template, visibili nell'Explorer, non modificabili. */
    private static final List<String> IMAGES = List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");
    private static final List<String> REFERENCES = List.of(".pdf");

    // ===== Tipi di file =====

    private static boolean isSupportedFile(String name) {
        String n = name.toLowerCase();
        return EDITABLE.stream().anyMatch(n::endsWith) || IMAGES.stream().anyMatch(n::endsWith)
                || REFERENCES.stream().anyMatch(n::endsWith);
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase();
        return IMAGES.stream().anyMatch(n::endsWith);
    }

    /** Tipo di file: html, css, json, img (null se non supportato). */
    public static String kindOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "html";
        if (n.endsWith(".css")) return "css";
        if (n.endsWith(".json")) return "json";
        if (isImage(n)) return "img";
        if (REFERENCES.stream().anyMatch(n::endsWith)) return "ref";
        return null;
    }

    /** Nodo dell'albero file per l'Explorer. */
    public record TreeNode(String name, String path, String type, String kind, List<TreeNode> children) {}

    /**
     * Albero ricorsivo del workspace per l'Explorer: prima le directory, poi i file, ordine alfabetico.
     */
    public TreeNode buildTree(Path workspaceDir) throws IOException {
        return buildDirNode(snapshotRoot(workspaceDir), "");
    }

    private TreeNode buildDirNode(Path dir, String relPath) throws IOException {
        List<TreeNode> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                    })
                    .toList();
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) continue; // repo git e file nascosti
                String childRel = relPath.isEmpty() ? name : relPath + "/" + name;
                if (Files.isDirectory(entry)) {
                    children.add(buildDirNode(entry, childRel));
                } else if (isSupportedFile(name)) {
                    children.add(new TreeNode(name, childRel, "file", kindOf(name), List.of()));
                }
            }
        }
        return new TreeNode(dir.getFileName().toString(), relPath, "dir", null, children);
    }

    // ===== I/O =====

    /** Radice dell'area di lavoro: tutti i percorsi file sono relativi a <workspace>/snapshot. */
    public Path snapshotRoot(Path workspaceDir) {
        return workspaceDir.resolve(SNAPSHOT_DIR);
    }

    /** Radice delle release pubblicate: <workspace>/release. */
    public Path releaseRoot(Path workspaceDir) {
        return workspaceDir.resolve(RELEASE_DIR);
    }

    /** Il workspace è nel formato attuale: snapshot/ con almeno un template .html. */
    public boolean isSnapshotWorkspace(Path path) {
        return isWorkspaceDirectory(path.resolve(SNAPSHOT_DIR));
    }

    /** Workspace nel formato precedente (file direttamente nella root): serve la migrazione. */
    public boolean isLegacyWorkspace(Path path) {
        return !isSnapshotWorkspace(path) && isWorkspaceDirectory(path);
    }

    public boolean isWorkspaceDirectory(Path path) {
        if (!Files.isDirectory(path)) return false;
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase().endsWith(".html"));
        } catch (IOException e) {
            return false;
        }
    }

    public String readFile(Path path) throws IOException {
        return Files.readString(path);
    }

    /**
     * Salva un file del workspace (sovrascrive). Il percorso è validato contro path-traversal.
     */
    public void saveFile(Path workspaceDir, String relPath, String content) throws IOException {
        Path target = snapshotRoot(workspaceDir).resolve(relPath).normalize();
        if (!target.startsWith(snapshotRoot(workspaceDir).toAbsolutePath().normalize())) {
            throw new IOException("Percorso fuori dal workspace: " + relPath);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content == null ? "" : content);
    }

    /**
     * Serve un'immagine del workspace (per l'anteprima nei template). Percorso validato contro traversal.
     */
    public byte[] readImage(Path workspaceDir, String relPath) throws IOException {
        Path target = snapshotRoot(workspaceDir).resolve(relPath).normalize();
        if (!target.startsWith(snapshotRoot(workspaceDir).toAbsolutePath().normalize())) {
            throw new IOException("Percorso fuori dal workspace: " + relPath);
        }
        String n = relPath.toLowerCase();
        if (!isImage(n) && !n.endsWith(".pdf")) {
            throw new IOException("Asset non servibile: " + relPath);
        }
        return Files.readAllBytes(target);
    }

    // ===== Creazione / Rinomina (Explorer file ops) =====

    /** File di testo editabili nei quali cercare/aggiornare i riferimenti. */
    private static boolean isTextFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".html") || n.endsWith(".css") || n.endsWith(".json");
    }

    /** Impalcatura minima per un nuovo template .html. */
    public static String htmlScaffold(String title) {
        return "<!DOCTYPE html>\n<html lang=\"it\">\n<head>\n  <meta charset=\"utf-8\">\n  <title>"
                + title + "</title>\n</head>\n<body>\n\n</body>\n</html>\n";
    }

    /**
     * Valida un percorso relativo: segmenti non vuoti, senza punti-inizi (niente nascosti/.git),
     * releases/ riservata agli snapshot, lunghezza ragionevole. Lancia IllegalArgumentException
     * con messaggio presentabile all'utente.
     */
    private void validateRelPath(String relPath) {
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

    private Path resolveInsideWorkspace(Path workspaceDir, String relPath) {
        Path root = snapshotRoot(workspaceDir).toAbsolutePath().normalize();
        Path target = root.resolve(relPath.replace('\\', '/')).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Percorso fuori dal workspace: " + relPath);
        }
        return target;
    }

    /**
     * Crea un file o una cartella nel workspace. Un nuovo .html nasce con lo scaffold minimo,
     * un .json con {}, gli altri file vuoti.
     *
     * @throws FileAlreadyExistsException se il percorso esiste già
     */
    public void createNode(Path workspaceDir, String relPath, boolean isDir) throws IOException {
        validateRelPath(relPath);
        Path target = resolveInsideWorkspace(workspaceDir, relPath);
        if (Files.exists(target)) throw new FileAlreadyExistsException(relPath);
        if (isDir) {
            Files.createDirectories(target);
            return;
        }
        Files.createDirectories(target.getParent());
        String name = target.getFileName().toString();
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) {
            String title = name.replaceAll("(?i)\\.html$", "").replace('-', ' ').replace('_', ' ').trim();
            Files.writeString(target, htmlScaffold(title.isBlank() ? "Nuovo documento" : title));
        } else if (lower.endsWith(".json")) {
            Files.writeString(target, "{}\n");
        } else {
            Files.writeString(target, "");
        }
    }

    /** Coppia (percorso vecchio → nuovo) restituita dalla rinomina. */
    public record MovedFile(String from, String to) {}

    /** Percorso del file accoppiato (X.html ↔ X.json, stessa cartella): null se non applicabile. */
    private static String pairedPath(String rel) {
        String lower = rel.toLowerCase();
        if (lower.endsWith(".html")) return rel.substring(0, rel.length() - 5) + ".json";
        if (lower.endsWith(".json")) return rel.substring(0, rel.length() - 5) + ".html";
        return null;
    }

    /**
     * Rinomina/muove un file o una cartella dentro il workspace. Se il file è accoppiato
     * (X.html ↔ X.json), la coppia si muove insieme: è il vincolo strutturale del modello.
     *
     * @return la lista degli spostamenti effettuati (include la coppia)
     * @throws FileAlreadyExistsException se la destinazione esiste già
     */
    public List<MovedFile> renameNode(Path workspaceDir, String fromRel, String toRel) throws IOException {
        validateRelPath(fromRel);
        validateRelPath(toRel);
        Path from = resolveInsideWorkspace(workspaceDir, fromRel);
        Path to = resolveInsideWorkspace(workspaceDir, toRel);
        if (!Files.exists(from)) throw new FileNotFoundException("Non trovato: " + fromRel);
        if (Files.exists(to)) throw new FileAlreadyExistsException(toRel);
        if (to.getParent() == null || !Files.isDirectory(to.getParent())) {
            throw new IllegalArgumentException("Cartella di destinazione inesistente: " + toRel);
        }
        Files.move(from, to);
        List<MovedFile> moved = new ArrayList<>();
        moved.add(new MovedFile(fromRel, toRel));
        String pairedFrom = pairedPath(fromRel);
        String pairedTo = pairedPath(toRel);
        Path snapshot = snapshotRoot(workspaceDir);
        if (pairedFrom != null && Files.exists(snapshot.resolve(pairedFrom).normalize())
                && !Files.exists(snapshot.resolve(pairedTo).normalize())) {
            Files.move(snapshot.resolve(pairedFrom).normalize(), snapshot.resolve(pairedTo).normalize());
            moved.add(new MovedFile(pairedFrom, pairedTo));
        }
        return moved;
    }

    /** Riferimento trovato nei template: file contenitore + riga + se è aggiornabile automaticamente. */
    public record ReferenceHit(String file, String snippet, boolean autoFixable) {}

    /**
     * Stringhe da cercare quando si rinomina fromRel: per i file il percorso completo e la
     * variabile senza estensione (include Thymeleaf ~{percorso :: frag}); per le cartelle il
     * prefisso con slash finale. Ordine: prima la variante più lunga.
     */
    private List<String> needlesFor(String fromRel) {
        String p = fromRel.replace('\\', '/');
        List<String> needles = new ArrayList<>();
        if (p.matches("(?i).*\\.(html|css|json)$")) {
            needles.add(p);
            needles.add(p.replaceFirst("(?i)\\.[a-z0-9]+$", ""));
        } else {
            needles.add(p.endsWith("/") ? p : p + "/");
        }
        return needles;
    }

    /** Controparti di sostituzione di needlesFor(), stesso ordine. */
    private List<String> replacementsFor(String fromRel, String toRel) {
        String f = fromRel.replace('\\', '/');
        String t = toRel.replace('\\', '/');
        List<String> replacements = new ArrayList<>();
        if (f.matches("(?i).*\\.(html|css|json)$")) {
            replacements.add(t);
            replacements.add(t.replaceFirst("(?i)\\.[a-z0-9]+$", ""));
        } else {
            replacements.add(t.endsWith("/") ? t : t + "/");
        }
        return replacements;
    }

    /**
     * Cerca nei file di testo del workspace i riferimenti letterali al percorso che si sta
     * rinominando (dry-run per il dialogo di proposta). Salta releases/, i file nascosti e il
     * file rinominato stesso.
     */
    public List<ReferenceHit> scanReferences(Path workspaceDir, String fromRel) throws IOException {
        validateRelPath(fromRel);
        List<String> needles = needlesFor(fromRel);
        List<ReferenceHit> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : textFilesWithContent(workspaceDir).entrySet()) {
            if (e.getKey().equals(fromRel.replace('\\', '/'))) continue;
            for (String line : e.getValue().split("\n", -1)) {
                for (String needle : needles) {
                    // per la variante senza estensione evita finto positivo "header" dentro "header-v2"
                    boolean match = needle.endsWith("/") ? line.contains(needle)
                            : containsReference(line, needle);
                    if (match) {
                        hits.add(new ReferenceHit(e.getKey(), line.trim(), true));
                        break;
                    }
                }
            }
        }
        return hits;
    }

    /** Match del needle escludendo false positive tipo "header" dentro "header-v2". */
    private static boolean containsReference(String line, String needle) {
        int idx = line.indexOf(needle);
        while (idx >= 0) {
            int end = idx + needle.length();
            if (end >= line.length()) return true;
            char c = line.charAt(end);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-')) return true;
            idx = line.indexOf(needle, idx + 1);
        }
        return false;
    }

    /**
     * Cancella un file (con la sua coppia json/html se accoppiata) o una cartella VUOTA
     * dall'area di lavoro. Le cartelle non vuote vengono rifiutate. Solo snapshot: le release
     * pubblicate non sono toccabili.
     *
     * @return i percorsi effettivamente cancellati (include la coppia)
     */
    public List<String> deleteNode(Path workspaceDir, String relPath) throws IOException {
        validateRelPath(relPath);
        Path target = resolveInsideWorkspace(workspaceDir, relPath);
        if (!Files.exists(target)) throw new FileNotFoundException("Non trovato: " + relPath);
        List<String> deleted = new ArrayList<>();
        String rel = relPath.replace('\\', '/');
        if (Files.isDirectory(target)) {
            try (Stream<Path> entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("La cartella non è vuota: svuotala prima di cancellarla");
                }
            }
            Files.delete(target);
            deleted.add(rel);
            return deleted;
        }
        Files.delete(target);
        deleted.add(rel);
        String paired = pairedPath(rel);
        if (paired != null) {
            Path p = snapshotRoot(workspaceDir).resolve(paired).normalize();
            if (Files.exists(p)) {
                Files.delete(p);
                deleted.add(paired);
            }
        }
        return deleted;
    }

    /**
     * Riscrive i riferimenti al percorso vecchio con quello nuovo in tutti i file di testo
     * del workspace. Sostituzioni applicate in ordine (prima la variante più lunga, così
     * "A/x.html" non viene toccata due volte da "A/x").
     *
     * @return i percorsi dei file modificati
     */
    public List<String> updateReferences(Path workspaceDir, String fromRel, String toRel) throws IOException {
        List<String> needles = needlesFor(fromRel);
        List<String> replacements = replacementsFor(fromRel, toRel);
        List<String> updated = new ArrayList<>();
        String movedSelf = toRel.replace('\\', '/');
        Path snapshot = snapshotRoot(workspaceDir);
        for (Map.Entry<String, String> e : textFilesWithContent(workspaceDir).entrySet()) {
            String rel = e.getKey();
            if (rel.equals(movedSelf) || rel.equals(pairedPath(movedSelf))) continue;
            String before = e.getValue();
            String after = before;
            for (int i = 0; i < needles.size(); i++) {
                after = after.replace(needles.get(i), replacements.get(i));
            }
            if (!after.equals(before)) {
                Files.writeString(snapshot.resolve(rel), after);
                updated.add(rel);
            }
        }
        return updated;
    }

    /** Contenuto dei file di testo dell'area di snapshot (rel → contenuto); i nascosti sono esclusi. */
    private Map<String, String> textFilesWithContent(Path workspaceDir) throws IOException {
        Path root = snapshotRoot(workspaceDir).toAbsolutePath().normalize();
        Map<String, String> files = new java.util.LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".")) continue;
                if (!isTextFile(rel)) continue;
                try {
                    files.put(rel, Files.readString(p));
                } catch (Exception ignored) {
                    // file non leggibile (es. encoding strano): si salta
                }
            }
        }
        return files;
    }

    // ===== Render =====

    /**
     * Configurazione Thymeleaf standard: prefix = root del workspace, suffix = ".html", nome logico
     * senza estensione. Gli include th:replace (~{STANDARD/tabella-tariffe :: frag}) si risolvono
     * da qualunque cartella, perché tutti i percorsi sono relativi alla root.
     */
    private TemplateEngine engineFor(Path workspaceDir) {
        FileTemplateResolver resolver = new FileTemplateResolver();
        resolver.setPrefix(workspaceDir.toAbsolutePath() + "/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private Context contextWith(Map<String, Object> data) {
        Context context = new Context();
        if (data != null) data.forEach(context::setVariable);
        return context;
    }

    private Map<String, Object> parseJson(String jsonData) {
        if (jsonData == null || jsonData.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(jsonData, Map.class);
            return data;
        } catch (IOException e) {
            return Map.of("error", "Invalid JSON: " + e.getMessage());
        }
    }

    /** Render diretto da disco (nome logico senza estensione). */
    public String renderTemplate(Path templateDir, String templateName, String jsonData) {
        String logicalName = templateName.replaceAll("(?i)\\.html$", "");
        return engineFor(templateDir).process(logicalName, contextWith(parseJson(jsonData)));
    }

    /**
     * Render con overlay: copia il workspace in una directory temporanea, sovrascrive i file
     * con le versioni live (buffer modificati, CSS e JSON compresi) e renderizza da lì.
     * Copia TUTTI i file: gli asset (immagini) referenziati nei template restano risolvibili.
     */
    public String renderOverlayTemplate(Path workspaceDir, String templateName, Map<String, String> overlay, String jsonData) throws IOException {
        Path tmp = Files.createTempDirectory("pdforNotPdf-overlay");
        try {
            copyWorkspaceWithOverlay(workspaceDir, tmp, overlay);
            return renderTemplate(tmp, templateName, jsonData);
        } finally {
            deleteRecursively(tmp);
        }
    }

    private void copyWorkspaceWithOverlay(Path src, Path dst, Map<String, String> overlay) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = src.relativize(p).toString().replace('\\', '/');
                Path target = dst.resolve(rel);
                Files.createDirectories(target.getParent());
                String live = overlay.get(rel);
                if (live != null) {
                    Files.writeString(target, live);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        }
    }

    /** Inietta il CSS come <style> prima di </head> (usato dalla REST API per il parametro cssFile). */
    public String injectCss(String html, String cssContent) {
        return (cssContent != null && !cssContent.isBlank())
                ? html.replace("</head>", "<style>" + cssContent + "</style>\n</head>")
                : html;
    }

    // ===== Render di un documento completo (contratto dell'app) =====

    /** Destinazione del render: determina come vengono risolte le immagini. */
    public enum AssetTarget { PREVIEW, PRINT }

    /**
     * Renderizza un documento nello stato corrente del workspace.
     *
     * Contratto:
     *  - il JSON è accoppiato alla pagina per nome e cartella (X/fattura.html → X/fattura.json);
     *    vince il buffer dirty, altrimenti il disco, altrimenti nessun dato;
     *  - i <link rel="stylesheet"> nel template vengono INLINE-ATI: vince il buffer dirty,
     *    altrimenti il disco; percorsi relativi alla root del workspace;
     *  - le <img> vengono riscritte: in PREVIEW verso l'endpoint /workspace/asset,
     *    in PRINT come data-URI (PDF self-contained).
     */
    public String renderDocument(Path workspaceDir, String templateRel, Map<String, String> overlay, AssetTarget target) throws IOException {
        Path snapshot = snapshotRoot(workspaceDir);
        Map<String, String> ov = overlay != null ? overlay : Map.of();
        String jsonRel = templateRel.replaceAll("(?i)\\.html$", ".json");
        String jsonContent = ov.containsKey(jsonRel) ? ov.get(jsonRel) : readQuiet(snapshot.resolve(jsonRel));

        String html = ov.isEmpty()
                ? renderTemplate(snapshot, templateRel, jsonContent)
                : renderOverlayTemplate(snapshot, templateRel, ov, jsonContent);

        return inlineLinkedAssets(html, snapshot, ov, target);
    }

    /**
     * Inline dei <link rel="stylesheet"> e rewrite delle <img> sull'HTML renderizzato.
     */
    public String inlineLinkedAssets(String html, Path workspaceDir, Map<String, String> overlay, AssetTarget target) {
        Map<String, String> ov = overlay != null ? overlay : Map.of();
        Document doc = Jsoup.parse(html);
        String root = workspaceDir.toAbsolutePath().toString();

        for (Element link : doc.select("link[rel~=stylesheet]")) {
            String href = link.attr("href");
            if (isExternal(href)) continue;
            String rel = normalizeRel(href);
            String css = ov.containsKey(rel) ? ov.get(rel) : readQuiet(Path.of(root, rel));
            Element style = doc.createElement("style");
            style.text(css != null ? css : "/* CSS non trovato: " + href + " */");
            link.replaceWith(style);
        }

        for (Element img : doc.select("img[src]")) {
            String src = img.attr("src");
            if (isExternal(src)) continue;
            String rel = normalizeRel(src);
            switch (target) {
                case PREVIEW -> img.attr("src",
                        "/workspace/asset?workspace=" + urlEncode(root) + "&file=" + urlEncode(rel));
                case PRINT -> {
                    byte[] bytes = readQuietBytes(Path.of(root, rel));
                    if (bytes != null) {
                        img.attr("src", "data:" + mimeFor(rel) + ";base64," + Base64.getEncoder().encodeToString(bytes));
                    }
                }
            }
        }

        // Serializzazione XML well-formed: OpenHTMLtoPDF usa un parser XML strict
        // (prettyPrint off per non introdurre whitespace dentro gli elementi inline)
        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
                .prettyPrint(false);
        return doc.outerHtml();
    }

    private static boolean isExternal(String url) {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:") || url.startsWith("//");
    }

    /** Percorsi relativi alla root del workspace; una "/" iniziale è facoltativa e ignorata. */
    private static String normalizeRel(String href) {
        String rel = href.replace('\\', '/');
        while (rel.startsWith("/") || rel.startsWith("./")) {
            rel = rel.startsWith("/") ? rel.substring(1) : rel.substring(2);
        }
        return rel;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String readQuiet(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] readQuietBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static String mimeFor(String rel) {
        String n = rel.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

}
