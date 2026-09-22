package io.github.ocuda.pdfornotpdf.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test unitari di WorkspaceService: albero multi-tenant (STANDARD/CLIENTI), render Thymeleaf
 * con include cross-cartella, inline di CSS linkati e immagini, overlay dei buffer dirty,
 * salvataggio e guardia path-traversal.
 */
class WorkspaceServiceTest {

    private final WorkspaceService service = new WorkspaceService();

    @TempDir
    Path ws;

    /** Percorso dentro l'area di lavoro (snapshot/). */
    private Path snap(String rel) { return ws.resolve("snapshot").resolve(rel); }

    @BeforeEach
    void setUp() throws IOException {
        // Struttura multi-tenant: root con STANDARD/ e CLIENTE_A/ (nessun .html direttamente in root)
        Files.createDirectories(snap("STANDARD/include"));
        Files.createDirectories(snap("CLIENTE_A/include"));
        Files.createDirectories(snap("assets"));

        // Frammento condiviso cross-cartella
        Files.writeString(snap("STANDARD/tabella-tariffe.html"),
                "<table th:fragment=\"tariffe\"><tr><td>TARIFFE</td></tr></table>");

        // CSS: uno STANDARD, uno del cliente
        Files.writeString(snap("STANDARD/include/common.css"), "body { color: #111; }\n");
        Files.writeString(snap("CLIENTE_A/include/common.css"), "h1 { color: #dc2626; }\n");

        // Header del cliente con immagine
        Files.writeString(snap("CLIENTE_A/include/header.html"),
                "<header th:fragment=\"header\"><img src=\"assets/logo.png\" /></header>");
        Files.write(snap("assets/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        // Template del cliente: 2 CSS (STANDARD + cliente) + frammento STANDARD + header con img
        Files.writeString(snap("CLIENTE_A/preventivo.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head>
                  <link rel="stylesheet" href="STANDARD/include/common.css" />
                  <link rel="stylesheet" href="CLIENTE_A/include/common.css" />
                </head>
                <body>
                  <div th:replace="~{CLIENTE_A/include/header :: header}"></div>
                  <h1 th:text="${titolo}">Titolo</h1>
                  <div th:replace="~{STANDARD/tabella-tariffe :: tariffe}"></div>
                </body>
                </html>
                """);
        Files.writeString(snap("CLIENTE_A/preventivo.json"), "{\"titolo\":\"Preventivo A\"}");
    }

    // ===== Tree =====

    @Test
    void buildTreeIsRecursiveAndIncludesImages() throws IOException {
        WorkspaceService.TreeNode root = service.buildTree(ws);

        List<String> names = root.children().stream().map(WorkspaceService.TreeNode::name).toList();
        assertTrue(names.contains("STANDARD"));
        assertTrue(names.contains("CLIENTE_A"));
        assertTrue(names.contains("assets"));

        WorkspaceService.TreeNode assets = root.children().stream()
                .filter(n -> n.name().equals("assets")).findFirst().orElseThrow();
        assertEquals("img", assets.children().get(0).kind(), "le immagini compaiono con kind 'img'");
    }

    @Test
    void buildTreePutsDirectoriesFirstAndSortsAlphabetically() throws IOException {
        Files.createDirectories(snap("AAA"));
        WorkspaceService.TreeNode root = service.buildTree(ws);
        List<String> names = root.children().stream().map(WorkspaceService.TreeNode::name).toList();
        assertTrue(names.indexOf("AAA") < names.indexOf("CLIENTE_A"), "le directory vengono prima");
        assertTrue(names.indexOf("CLIENTE_A") < names.indexOf("STANDARD"), "ordine alfabetico");
    }

    // ===== Validazione =====

    @Test
    void isWorkspaceDirectoryIsRecursive() throws IOException {
        assertTrue(service.isWorkspaceDirectory(ws),
                "la root può contenere solo cartelle: basta un .html in una sottocartella");
        assertFalse(service.isWorkspaceDirectory(snap("assets")));
        assertFalse(service.isWorkspaceDirectory(snap("non-esiste")));
    }

    // ===== Render: include cross-cartella =====

    @Test
    void renderTemplateResolvesCrossFolderIncludes() {
        String html = service.renderTemplate(ws.resolve("snapshot"), "CLIENTE_A/preventivo.html", "{\"titolo\":\"Bozza\"}");
        assertTrue(html.contains("TARIFFE"), "il frammento STANDARD risolve da un template CLIENTE_A");
        assertTrue(html.contains("Bozza"));
    }

    // ===== Render document: inline CSS multipli e immagini =====

    @Test
    void renderDocumentPreviewInlinesMultipleCssAndRewritesImages() throws IOException {
        String html = service.renderDocument(ws, "CLIENTE_A/preventivo.html", Map.of(), WorkspaceService.AssetTarget.PREVIEW);

        assertTrue(html.contains("body { color: #111; }"), "CSS STANDARD inline-ato");
        assertTrue(html.contains("h1 { color: #dc2626; }"), "CSS CLIENTE_A inline-ato");
        assertTrue(html.indexOf("body { color: #111; }") < html.indexOf("h1 { color: #dc2626; }"),
                "ordine di cascata dei <link> preservato");
        assertTrue(html.contains("/workspace/asset?workspace="), "immagine riscritta verso l'endpoint asset");
        assertFalse(html.contains("<link"), "nessun <link> residuo");
    }

    @Test
    void renderDocumentPrintEmbedsImagesAsDataUri() throws IOException {
        String html = service.renderDocument(ws, "CLIENTE_A/preventivo.html", Map.of(), WorkspaceService.AssetTarget.PRINT);

        assertTrue(html.contains("src=\"data:image/png;base64,"), "immagine incorporata come data-URI nel PDF");
        assertFalse(html.contains("/workspace/asset"), "in stampa l'immagine non punta all'endpoint");
    }

    @Test
    void renderDocumentOverlayPrefersDirtyCssAndTemplates() throws IOException {
        String dirtyTemplate = """
                <html xmlns:th="http://www.thymeleaf.org">
                <head><link rel="stylesheet" href="CLIENTE_A/include/common.css" /></head>
                <body><p>MARKER-LIVE</p></body>
                </html>
                """;
        String html = service.renderDocument(ws, "CLIENTE_A/preventivo.html",
                Map.of("CLIENTE_A/preventivo.html", dirtyTemplate,
                       "CLIENTE_A/include/common.css", "h1 { color: LIVE; }"),
                WorkspaceService.AssetTarget.PREVIEW);

        assertTrue(html.contains("MARKER-LIVE"), "il template dirty vince sul disco");
        assertTrue(html.contains("h1 { color: LIVE; }"), "il CSS dirty vince sul disco");
        assertFalse(html.contains("#dc2626"), "il CSS su disco viene ignorato quando è dirty");
    }

    @Test
    void renderDocumentPairsJsonByFolderAndName() throws IOException {
        String html = service.renderDocument(ws, "CLIENTE_A/preventivo.html", Map.of(), WorkspaceService.AssetTarget.PREVIEW);
        assertTrue(html.contains("Preventivo A"), "il JSON accoppiato per nome+cartella viene caricato");
    }

    @Test
    void renderDocumentWithoutJsonFileStillRenders() throws IOException {
        Files.delete(snap("CLIENTE_A/preventivo.json"));
        String html = service.renderDocument(ws, "CLIENTE_A/preventivo.html", Map.of(), WorkspaceService.AssetTarget.PREVIEW);
        System.out.println("HTML>>>" + html + "<<<");
        assertTrue(html.contains("<h1></h1>"), "senza JSON th:text con variabile assente renderizza vuoto (per questo il json è obbligatorio per convenzione)");
    }

    // ===== Save =====

    @Test
    void saveFileWritesContent() throws IOException {
        service.saveFile(ws, "CLIENTE_A/nuovo.html", "nuovo contenuto");
        assertEquals("nuovo contenuto", Files.readString(snap("CLIENTE_A/nuovo.html")));
    }

    @Test
    void saveFileRejectsPathTraversal() {
        assertThrows(IOException.class, () -> service.saveFile(ws, "../evil.html", "x"));
        assertThrows(IOException.class, () -> service.saveFile(ws, "a/../../evil.html", "x"));
    }

    // ===== Immagini =====

    @Test
    void readImageServesOnlyImagesInsideWorkspace() throws IOException {
        byte[] logo = service.readImage(ws, "assets/logo.png");
        assertEquals(0x89, logo[0] & 0xFF, "magic byte PNG");
        assertThrows(IOException.class, () -> service.readImage(ws, "../fuori.png"));
        assertThrows(IOException.class, () -> service.readImage(ws, "CLIENTE_A/preventivo.html"),
                "un .html non è un'immagine servibile");
    }
}
