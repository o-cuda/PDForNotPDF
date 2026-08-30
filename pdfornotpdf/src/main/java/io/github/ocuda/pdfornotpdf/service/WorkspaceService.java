package io.github.ocuda.pdfornotpdf.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.FileTemplateResolver;

import java.io.IOException;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Estensioni modificabili nell'editor. */
    private static final List<String> EDITABLE = List.of(".html", ".css", ".json");
    /** Estensioni immagine: referenziabili nei template, visibili nell'Explorer, non modificabili. */
    private static final List<String> IMAGES = List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");

    // ===== Tipi di file =====

    private static boolean isSupportedFile(String name) {
        String n = name.toLowerCase();
        return EDITABLE.stream().anyMatch(n::endsWith) || IMAGES.stream().anyMatch(n::endsWith);
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
        return null;
    }

    /** Nodo dell'albero file per l'Explorer. */
    public record TreeNode(String name, String path, String type, String kind, List<TreeNode> children) {}

    /**
     * Albero ricorsivo del workspace per l'Explorer: prima le directory, poi i file, ordine alfabetico.
     */
    public TreeNode buildTree(Path workspaceDir) throws IOException {
        return buildDirNode(workspaceDir, "");
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

    public String readFile(Path path) throws IOException {
        return Files.readString(path);
    }

    /**
     * Salva un file del workspace (sovrascrive). Il percorso è validato contro path-traversal.
     */
    public void saveFile(Path workspaceDir, String relPath, String content) throws IOException {
        Path target = workspaceDir.resolve(relPath).normalize();
        if (!target.startsWith(workspaceDir.toAbsolutePath().normalize())) {
            throw new IOException("Percorso fuori dal workspace: " + relPath);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content == null ? "" : content);
    }

    /**
     * Serve un'immagine del workspace (per l'anteprima nei template). Percorso validato contro traversal.
     */
    public byte[] readImage(Path workspaceDir, String relPath) throws IOException {
        Path target = workspaceDir.resolve(relPath).normalize();
        if (!target.startsWith(workspaceDir.toAbsolutePath().normalize()) || !isImage(relPath)) {
            throw new IOException("Immagine non valida: " + relPath);
        }
        return Files.readAllBytes(target);
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
        Map<String, String> ov = overlay != null ? overlay : Map.of();
        String jsonRel = templateRel.replaceAll("(?i)\\.html$", ".json");
        String jsonContent = ov.containsKey(jsonRel) ? ov.get(jsonRel) : readQuiet(workspaceDir.resolve(jsonRel));

        String html = ov.isEmpty()
                ? renderTemplate(workspaceDir, templateRel, jsonContent)
                : renderOverlayTemplate(workspaceDir, templateRel, ov, jsonContent);

        return inlineLinkedAssets(html, workspaceDir, ov, target);
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

    // ===== Validazione =====

    /**
     * Un workspace è valido se contiene almeno un template .html, anche in sottocartelle
     * (la root del progetto può contenere solo le cartelle STANDARD/CLIENTE_x).
     */
    public boolean isWorkspaceDirectory(Path path) {
        if (!Files.isDirectory(path)) return false;
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase().endsWith(".html"));
        } catch (IOException e) {
            return false;
        }
    }
}
