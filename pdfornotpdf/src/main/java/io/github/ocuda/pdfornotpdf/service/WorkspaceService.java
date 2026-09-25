package io.github.ocuda.pdfornotpdf.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.AbstractConfigurableTemplateResolver;
import org.thymeleaf.templateresource.FileTemplateResource;
import org.thymeleaf.templateresource.ITemplateResource;
import org.thymeleaf.templateresource.StringTemplateResource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

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
     * Una sotto-cartella illeggibile (permessi) compare come vuota invece di far fallire l'albero.
     */
    public TreeNode buildTree(Path workspaceDir) {
        return buildDirNode(snapshotRoot(workspaceDir), "");
    }

    private TreeNode buildDirNode(Path dir, String relPath) {
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
        } catch (IOException | UncheckedIOException e) {
            log.warn("Cartella non esplorabile, mostrata come vuota: {} ({})", dir, e.getMessage());
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
        } catch (IOException | UncheckedIOException e) {
            // anche una sotto-cartella illeggibile (es. /tmp/systemd-private-*) non deve produrre un 500
            log.debug("Directory non esplorabile ({}): {}", path, e.getMessage());
            return false;
        }
    }

    public String readFile(Path path) throws IOException {
        return Files.readString(path);
    }

    /**
     * Salva un'immagine caricata dall'utente dentro l'area snapshot (audit 03 / S5+R6: la
     * validazione dei percorsi vive SOLO qui, segment-by-segment, come per le altre file-ops).
     *
     * @return il percorso relativo scritto (cartella + nome)
     * @throws IllegalArgumentException cartella o nome non validi (segmenti ".", ".." o nascosti)
     * @throws FileAlreadyExistsException se il percorso esiste già e replace è false
     */
    public String saveUploadedImage(Path workspaceDir, String folder, String fileName,
                                    java.io.InputStream in, boolean replace) throws IOException {
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("Nome file mancante");
        String name = fileName.replace('\\', '/');
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || name.startsWith(".")) {
            throw new IllegalArgumentException("Nome non valido: '" + name + "'");
        }
        String folderRel = (folder == null || folder.isBlank()) ? "assets"
                : folder.replace('\\', '/').replaceAll("^/+|/+$", "");
        WorkspacePaths.validateRelPath(folderRel + "/" + name);
        Path root = snapshotRoot(workspaceDir).toAbsolutePath().normalize();
        Path target = root.resolve(folderRel).resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Percorso fuori dal workspace");
        }
        if (Files.exists(target) && !replace) {
            throw new FileAlreadyExistsException(folderRel + "/" + name);
        }
        Files.createDirectories(target.getParent());
        try (in) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return folderRel + "/" + name;
    }

    /**
     * Salva un file del workspace (sovrascrive). Il percorso è validato contro path-traversal.
     */
    public void saveFile(Path workspaceDir, String relPath, String content) throws IOException {
        Path target = WorkspacePaths.resolveInside(snapshotRoot(workspaceDir), relPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content == null ? "" : content);
    }

    /**
     * Serve un'immagine del workspace (per l'anteprima nei template). Percorso validato contro traversal.
     */
    public byte[] readImage(Path workspaceDir, String relPath) throws IOException {
        Path target = WorkspacePaths.resolveInside(snapshotRoot(workspaceDir), relPath);
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
     * Crea un file o una cartella nel workspace. Un nuovo .html nasce con lo scaffold minimo,
     * un .json con {}, gli altri file vuoti.
     *
     * @throws FileAlreadyExistsException se il percorso esiste già
     */
    public void createNode(Path workspaceDir, String relPath, boolean isDir) throws IOException {
        WorkspacePaths.validateRelPath(relPath);
        Path target = WorkspacePaths.resolveInside(snapshotRoot(workspaceDir), relPath);
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
        WorkspacePaths.validateRelPath(fromRel);
        WorkspacePaths.validateRelPath(toRel);
        Path from = WorkspacePaths.resolveInside(snapshotRoot(workspaceDir), fromRel);
        Path to = WorkspacePaths.resolveInside(snapshotRoot(workspaceDir), toRel);
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
        WorkspacePaths.validateRelPath(fromRel);
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
        WorkspacePaths.validateRelPath(relPath);
        Path target = WorkspacePaths.resolveInside(snapshotRoot(workspaceDir), relPath);
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

    /** Contenuto dei file di testo dell'area di snapshot (rel → contenuto); i nascosti sono esclusi.
     *  Una sotto-cartella illeggibile degrada a contenuto parziale invece di far fallire l'operazione. */
    private Map<String, String> textFilesWithContent(Path workspaceDir) {
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
        } catch (IOException | UncheckedIOException e) {
            log.warn("Scan file di testo parziale (sotto-cartella illeggibile in {}): {}", root, e.getMessage());
        }
        return files;
    }

    // ===== Render =====

    /** Cache dei TemplateEngine per radice di workspace (R5): l'engine è thread-safe e con
     *  resolver cacheable=false ricontenta sempre il disco, quindi il contenuto è sempre fresco. */
    private final Map<Path, TemplateEngine> engineCache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, TemplateEngine> eldest) {
                    return size() > 4;
                }
            });

    /**
     * Resolver Thymeleaf con guardia di percorso (audit 03 / S1) e overlay in memoria (R5):
     * - un riferimento che esce dal workspace (~{"../fuori"}) NON viene mai letto dal disco;
     * - i buffer dirty (overlay) risolvono senza copiare il workspace in una temp dir.
     */
    private final class GuardedOverlayResolver extends AbstractConfigurableTemplateResolver {

        private final Path root;
        private final Map<String, String> overlay;

        GuardedOverlayResolver(Path root, Map<String, String> overlay) {
            this.root = root.toAbsolutePath().normalize();
            this.overlay = overlay == null ? Map.of() : overlay;
            setTemplateMode("HTML");
            setSuffix(".html");
            setCacheable(false);
            setCheckExistence(true);
        }

        @Override
        protected ITemplateResource computeTemplateResource(IEngineConfiguration configuration,
                                                            String ownerTemplate, String template,
                                                            String prefix, String suffix,
                                                            Map<String, Object> templateResolutionAttributes) {
            String rel = (template.endsWith(".html") ? template : template + ".html").replace('\\', '/');
            Path p = WorkspacePaths.resolveInsideOrNull(root, rel);
            if (p == null) {
                log.warn("Riferimento template fuori dal workspace scartato: {} (radice {})", rel, root);
                // risorsa inesistente → TemplateInputException pulita (400 dal GlobalExceptionHandler)
                return new FileTemplateResource(root.resolve("__fuori_dal_workspace__.html").toFile(), null);
            }
            String live = overlay.get(rel);
            if (live != null) return new StringTemplateResource(live);
            return new FileTemplateResource(p.toFile(), null);
        }
    }

    private TemplateEngine engineFor(Path templateDir, Map<String, String> overlay) {
        Path root = templateDir.toAbsolutePath().normalize();
        boolean noOverlay = overlay == null || overlay.isEmpty();
        if (noOverlay) {
            synchronized (engineCache) {
                TemplateEngine cached = engineCache.get(root);
                if (cached != null) return cached;
            }
        }
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(new GuardedOverlayResolver(root, overlay));
        if (noOverlay) {
            synchronized (engineCache) {
                engineCache.put(root, engine);
            }
        }
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

    /** Render diretto da disco (nome logico senza estensione). Engine riusato per radice (R5). */
    public String renderTemplate(Path templateDir, String templateName, String jsonData) {
        String logicalName = templateName.replaceAll("(?i)\\.html$", "");
        return engineFor(templateDir, Map.of()).process(logicalName, contextWith(parseJson(jsonData)));
    }

    /**
     * Render con overlay: i buffer live (modificati, CSS e JSON compresi) risolvono in memoria
     * nel resolver (R5), senza più copia del workspace in temp dir a ogni anteprima. Gli asset
     * non dirty restano su disco: le immagini non passano dal resolver ma da inlineLinkedAssets.
     */
    public String renderOverlayTemplate(Path workspaceDir, String templateName, Map<String, String> overlay, String jsonData) {
        String logicalName = templateName.replaceAll("(?i)\\.html$", "");
        return engineFor(workspaceDir, overlay).process(logicalName, contextWith(parseJson(jsonData)));
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
        String jsonContent = ov.containsKey(jsonRel) ? ov.get(jsonRel) : WorkspacePaths.readTextOrNull(snapshot, jsonRel);

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
            if (WorkspacePaths.isExternal(href)) continue;
            String rel = WorkspacePaths.normalizeRel(href);
            // guardia S1: un riferimento che esce dal workspace non viene mai letto dal disco
            String css = WorkspacePaths.staysInside(rel) ? (ov.containsKey(rel) ? ov.get(rel) : WorkspacePaths.readTextOrNull(workspaceDir, rel)) : null;
            Element style = doc.createElement("style");
            style.text(css != null ? css : "/* CSS non trovato: " + href + " */");
            link.replaceWith(style);
        }

        for (Element img : doc.select("img[src]")) {
            String src = img.attr("src");
            if (WorkspacePaths.isExternal(src)) continue;
            String rel = WorkspacePaths.normalizeRel(src);
            if (!WorkspacePaths.staysInside(rel)) continue; // guardia S1: niente data-URI con file esterni allo snapshot
            switch (target) {
                case PREVIEW -> img.attr("src",
                        "/workspace/asset?workspace=" + urlEncode(root) + "&file=" + urlEncode(rel));
                case PRINT -> {
                    byte[] bytes = WorkspacePaths.readBytesOrNull(workspaceDir, rel);
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

    /** Mime type per estensione (unico per asset, viewer immagini e stampe — audit 05/K3). */
    public static String mimeFor(String rel) {
        String n = rel.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

}
