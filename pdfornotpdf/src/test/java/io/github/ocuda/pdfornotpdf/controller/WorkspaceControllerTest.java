package io.github.ocuda.pdfornotpdf.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test del layer HTTP di WorkspaceController (MockMvc con contesto Spring reale:
 * il fragment Thymeleaf viene renderizzato dal vero motore).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WorkspaceController controller;

    @TempDir
    Path ws;

    /** Percorso dentro l'area di lavoro (snapshot/). */
    private Path snap(String rel) { return ws.resolve("snapshot").resolve(rel); }

    @BeforeEach
    void setUp() throws IOException {
        // Layout multi-tenant minimale
        Files.createDirectories(snap("STANDARD/include"));
        Files.createDirectories(snap("CLIENTE_A/include"));
        Files.createDirectories(snap("assets"));

        Files.writeString(snap("STANDARD/tabella-tariffe.html"),
                "<table th:fragment=\"tariffe\"><tr><td>TARIFFE</td></tr></table>");
        Files.writeString(snap("STANDARD/include/common.css"), "body { color: #111; }\n");
        Files.writeString(snap("CLIENTE_A/include/common.css"), "h1 { color: #dc2626; }\n");
        Files.write(snap("assets/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        Files.writeString(snap("CLIENTE_A/preventivo.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head><link rel="stylesheet" href="STANDARD/include/common.css" />
                <link rel="stylesheet" href="CLIENTE_A/include/common.css" /></head>
                <body><h1 th:text="${titolo}">Titolo</h1></body>
                </html>
                """);
        Files.writeString(snap("CLIENTE_A/preventivo.json"), "{\"titolo\":\"Da disco\"}");
        Files.writeString(snap("fattura.css"), "h1 { color: red; }");
    }

    // ===== Loader / workspace (PRG) =====

    @Test
    void loadValidWorkspaceRedirectsToShareableUrl() throws Exception {
        mockMvc.perform(post("/workspace/load").param("path", ws.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/workspace?ws=" + java.net.URLEncoder.encode(ws.toString(), java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void loadInvalidWorkspaceShowsError() throws Exception {
        mockMvc.perform(post("/workspace/load").param("path", "/percorso/assente"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"))
                .andExpect(view().name("loader"));
    }

    @Test
    void workspaceWithoutWsAndSessionRedirectsToLoader() throws Exception {
        mockMvc.perform(get("/workspace"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // ===== Tree / file / asset =====

    @Test
    void treeReturnsRecursiveJsonWithImages() throws Exception {
        mockMvc.perform(get("/workspace/tree").param("workspace", ws.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("dir"))
                .andExpect(jsonPath("$.children[?(@.path=='STANDARD')].type").value("dir"))
                .andExpect(jsonPath("$.children[?(@.path=='assets')].children[0].kind").value("img"));
    }

    @Test
    void getFileReturnsContent() throws Exception {
        mockMvc.perform(get("/workspace/file")
                        .param("workspace", ws.toString())
                        .param("file", "fattura.css"))
                .andExpect(status().isOk())
                .andExpect(content().string("h1 { color: red; }"));
    }

    @Test
    void getFileRejectsPathTraversal() throws Exception {
        mockMvc.perform(get("/workspace/file")
                        .param("workspace", ws.toString())
                        .param("file", "../segreto.html"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assetServesWorkspaceImages() throws Exception {
        byte[] body = mockMvc.perform(get("/workspace/asset")
                        .param("workspace", ws.toString())
                        .param("file", "assets/logo.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.valueOf("image/png")))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(0x89, body[0] & 0xFF, "magic byte PNG");
    }

    @Test
    void assetRejectsTraversalAndNonImages() throws Exception {
        mockMvc.perform(get("/workspace/asset")
                        .param("workspace", ws.toString())
                        .param("file", "../fuori.png"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/workspace/asset")
                        .param("workspace", ws.toString())
                        .param("file", "CLIENTE_A/preventivo.html"))
                .andExpect(status().isBadRequest());
    }

    // ===== Save =====

    @Test
    void saveFileWritesToDisk() throws Exception {
        mockMvc.perform(post("/workspace/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"file\":\"CLIENTE_A/nuovo.html\",\"content\":\"ciao\"}"))
                .andExpect(status().isOk());

        assertEquals("ciao", Files.readString(snap("CLIENTE_A/nuovo.html")));
    }

    @Test
    void saveFileRejectsPathTraversal() throws Exception {
        mockMvc.perform(post("/workspace/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"file\":\"../evil.html\",\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    // ===== Preview (render + overlay) =====

    @Test
    void previewInlinesCssAndUsesPairedJson() throws Exception {
        mockMvc.perform(post("/workspace/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"template\":\"CLIENTE_A/preventivo.html\",\"dirtyFiles\":{}}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("body { color: #111; }"),
                        org.hamcrest.Matchers.containsString("h1 { color: #dc2626; }"),
                        org.hamcrest.Matchers.containsString("Da disco"))));
    }

    @Test
    void previewOverlayPrefersDirtyBuffersOverDisk() throws Exception {
        String dirtyTemplate = "<html xmlns:th=\"http://www.thymeleaf.org\"><head>"
                + "<link rel=\"stylesheet\" href=\"CLIENTE_A/include/common.css\" /></head>"
                + "<body><p>MARKER-LIVE</p></body></html>";

        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "workspace", ws.toString(),
                "template", "CLIENTE_A/preventivo.html",
                "dirtyFiles", java.util.Map.of(
                        "CLIENTE_A/preventivo.html", dirtyTemplate,
                        "CLIENTE_A/include/common.css", "h1 { color: LIVE; }")));

        mockMvc.perform(post("/workspace/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("MARKER-LIVE"),
                        org.hamcrest.Matchers.containsString("h1 { color: LIVE; }"))));
    }

    @Test
    void previewMissingTemplateReturnsErrorFragment() throws Exception {
        mockMvc.perform(post("/workspace/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"template\":\"assente.html\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Errore")));
    }

    // ===== Print =====

    @Test
    void printReturnsPdfWithDisposition() throws Exception {
        byte[] pdf = mockMvc.perform(post("/workspace/print")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"template\":\"CLIENTE_A/preventivo.html\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("preventivo.pdf")))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(pdf.length > 500, "PDF non vuoto");
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void printWithBrokenTemplateReturnsClean400() throws Exception {
        mockMvc.perform(post("/workspace/print")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"template\":\"assente.html\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Impossibile generare il PDF")));
    }

    @Test
    void loadLegacyWorkspaceSuggestsMigration() throws Exception {
        Path legacy = Files.createTempDirectory("legacy-ws");
        Files.writeString(legacy.resolve("fattura.html"), "<html><body>x</body></html>");
        mockMvc.perform(post("/workspace/load").param("path", legacy.toString()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("error", org.hamcrest.Matchers.containsString("migrate-workspace")))
                .andExpect(view().name("loader"));
    }

    // ===== Explorer file ops: creazione =====

    @Test
    void createHtmlFileWritesMinimalScaffold() throws Exception {
        mockMvc.perform(post("/workspace/node").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"CLIENTE_A/nuovo-doc.html\",\"type\":\"file\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("CLIENTE_A/nuovo-doc.html"));
        String html = Files.readString(snap("CLIENTE_A/nuovo-doc.html"));
        assertTrue(html.startsWith("<!DOCTYPE html>"), "scaffold con DOCTYPE");
        assertTrue(html.contains("charset=\"utf-8\""), "scaffold con charset");
        assertTrue(html.contains("<title>nuovo doc</title>"), "title derivato dal nome file");
        assertTrue(html.contains("</body>"), "scaffold completo di body");
    }

    @Test
    void createNodeRejectsDuplicate() throws Exception {
        mockMvc.perform(post("/workspace/node").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"CLIENTE_A/preventivo.html\",\"type\":\"file\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createNodeRejectsTraversalHiddenAndReserved() throws Exception {
        // traversal, segmenti nascosti e percorso riservato → 400
        for (String path : new String[]{"../fuga.html", "dir/.nascosto.html"}) {
            mockMvc.perform(post("/workspace/node").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"workspace\":\"" + ws + "\",\"path\":\"" + path + "\",\"type\":\"file\"}"))
                    .andExpect(status().isBadRequest());
        }
        // le sotto-cartelle mancanti della destinazione vengono create
        mockMvc.perform(post("/workspace/node").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"CLIENTE_A/sotto/x/y.html\",\"type\":\"file\"}"))
                .andExpect(status().isOk());
        assertTrue(Files.exists(snap("CLIENTE_A/sotto/x/y.html")));
    }

    @Test
    void createDirAppearsInTree() throws Exception {
        mockMvc.perform(post("/workspace/node").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"CLIENTE_B\",\"type\":\"dir\"}"))
                .andExpect(status().isOk());
        assertTrue(Files.isDirectory(snap("CLIENTE_B")));
        mockMvc.perform(get("/workspace/tree").param("workspace", ws.toString()))
                .andExpect(jsonPath("$.children[?(@.path=='CLIENTE_B')].type").value("dir"));
    }

    // ===== Upload immagini =====

    @Test
    void uploadImageSavesIntoFolderAndRejectsDuplicateAndBadExt() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        var part = new org.springframework.mock.web.MockMultipartFile("file", "nuovo-logo.png", "image/png", png);

        // cartella selezionata esplicita
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/upload-image")
                        .file(part)
                        .param("workspace", ws.toString())
                        .param("folder", "CLIENTE_A/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("CLIENTE_A/assets/nuovo-logo.png"));
        assertTrue(Files.exists(snap("CLIENTE_A/assets/nuovo-logo.png")));

        // duplicato → 409
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/upload-image")
                        .file(part)
                        .param("workspace", ws.toString())
                        .param("folder", "CLIENTE_A/assets"))
                .andExpect(status().isConflict());

        // estensione non supportata → 400
        var bad = new org.springframework.mock.web.MockMultipartFile("file", "script.html", "text/html", "x".getBytes());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/upload-image")
                        .file(bad)
                        .param("workspace", ws.toString())
                        .param("folder", "assets"))
                .andExpect(status().isBadRequest());

        // default folder = assets/
        var part2 = new org.springframework.mock.web.MockMultipartFile("file", "banner.webp", "image/webp", png);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/upload-image")
                        .file(part2)
                        .param("workspace", ws.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("assets/banner.webp"));
        assertTrue(Files.exists(snap("assets/banner.webp")));
    }

    // ===== Delete =====

    @Test
    void deleteBlocksReferencedFileAndReturnsReferencingList() throws Exception {
        mockMvc.perform(post("/workspace/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"STANDARD/include/common.css\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.referencedBy[0]").value("CLIENTE_A/preventivo.html"));
        assertTrue(Files.exists(snap("STANDARD/include/common.css")), "file referenziato NON cancellato");
    }

    @Test
    void deletePrimaDonnaRemovesPairedJson() throws Exception {
        mockMvc.perform(post("/workspace/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"CLIENTE_A/preventivo.html\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted.length()").value(2));
        assertFalse(Files.exists(snap("CLIENTE_A/preventivo.html")));
        assertFalse(Files.exists(snap("CLIENTE_A/preventivo.json")), "coppia json cancellata insieme");
    }

    @Test
    void deleteFolderOnlyIfEmpty() throws Exception {
        Files.createDirectories(snap("CLIENTE_B"));
        Files.writeString(snap("CLIENTE_B/bozza.html"), "<html></html>");
        mockMvc.perform(post("/workspace/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"CLIENTE_B\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workspace/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"CLIENTE_B/bozza.html\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/workspace/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"CLIENTE_B\"}"))
                .andExpect(status().isOk());
        assertFalse(Files.exists(snap("CLIENTE_B")));
    }

    @Test
    void deleteMissingReturns404() throws Exception {
        mockMvc.perform(post("/workspace/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"assente.html\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadReplaceOverwritesOnlyWhenRequested() throws Exception {
        var v1 = new org.springframework.mock.web.MockMultipartFile("file", "nuovo-logo.png", "image/png", new byte[]{1, 1, 1, 1});
        var v2 = new org.springframework.mock.web.MockMultipartFile("file", "nuovo-logo.png", "image/png", new byte[]{2, 2, 2, 2});
        var created = controller.uploadImage(ws.toString(), "assets", false, v1);
        assertEquals(200, created.getStatusCode().value());
        assertTrue(Files.exists(snap("assets/logo.png")));

        var conflict = controller.uploadImage(ws.toString(), "assets", false, v2);
        assertEquals(409, conflict.getStatusCode().value());
        assertArrayEquals(new byte[]{1, 1, 1, 1}, Files.readAllBytes(snap("assets/nuovo-logo.png")));

        var replaced = controller.uploadImage(ws.toString(), "assets", true, v2);
        assertEquals(200, replaced.getStatusCode().value());
        assertArrayEquals(new byte[]{2, 2, 2, 2}, Files.readAllBytes(snap("assets/nuovo-logo.png")));
    }

    // ===== Explorer file ops: rinomina =====

    @Test
    void renameMovesFileAndPairedJson() throws Exception {
        String body = "{\"workspace\":\"" + ws + "\",\"from\":\"CLIENTE_A/preventivo.html\",\"to\":\"CLIENTE_A/preventivo-v2.html\"}";
        mockMvc.perform(post("/workspace/rename").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved.length()").value(2))
                .andExpect(jsonPath("$.moved[0].to").value("CLIENTE_A/preventivo-v2.html"))
                .andExpect(jsonPath("$.moved[1].to").value("CLIENTE_A/preventivo-v2.json"));
        assertFalse(Files.exists(snap("CLIENTE_A/preventivo.html")));
        assertFalse(Files.exists(snap("CLIENTE_A/preventivo.json")));
        assertTrue(Files.exists(snap("CLIENTE_A/preventivo-v2.html")));
        assertTrue(Files.exists(snap("CLIENTE_A/preventivo-v2.json")));
    }

    @Test
    void renameScanFindsThymeleafIncludeWithoutExtension() throws Exception {
        Files.writeString(snap("CLIENTE_A/fattura.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <body><div th:replace="~{CLIENTE_A/preventivo :: blocco}"></div></body>
                </html>
                """);
        mockMvc.perform(post("/workspace/rename/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"from\":\"CLIENTE_A/preventivo.html\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.references.length()").value(1))
                .andExpect(jsonPath("$.references[0].file").value("CLIENTE_A/fattura.html"))
                .andExpect(jsonPath("$.references[0].autoFixable").value(true));
    }

    @Test
    void renameWithUpdateReferencesRewritesIncludes() throws Exception {
        Files.writeString(snap("CLIENTE_A/fattura.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <body><div th:replace="~{CLIENTE_A/preventivo :: blocco}"></div></body>
                </html>
                """);
        String body = "{\"workspace\":\"" + ws + "\",\"from\":\"CLIENTE_A/preventivo.html\","
                + "\"to\":\"CLIENTE_A/preventivo-v2.html\",\"updateReferences\":true}";
        mockMvc.perform(post("/workspace/rename").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated.length()").value(1));
        String fattura = Files.readString(snap("CLIENTE_A/fattura.html"));
        assertTrue(fattura.contains("~{CLIENTE_A/preventivo-v2 :: blocco}"), "include aggiornato");
        assertFalse(fattura.contains("~{CLIENTE_A/preventivo ::"), "vecchio riferimento rimosso");
    }

    @Test
    void renameFolderMovesContentAndUpdatesPrefixReferences() throws Exception {
        Files.createDirectories(snap("OFFERTE"));
        Files.writeString(snap("OFFERTE/tipo-b.html"),
                "<html><head><link rel=\"stylesheet\" href=\"OFFERTE/base.css\" /></head><body></body></html>");
        Files.writeString(snap("OFFERTE/base.css"), "h1 { color: blue; }");
        String body = "{\"workspace\":\"" + ws + "\",\"from\":\"OFFERTE\",\"to\":\"OFFERTE-2026\",\"updateReferences\":true}";
        mockMvc.perform(post("/workspace/rename").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved[0].from").value("OFFERTE"))
                .andExpect(jsonPath("$.updated.length()").value(1));
        String html = Files.readString(snap("OFFERTE-2026/tipo-b.html"));
        assertTrue(html.contains("OFFERTE-2026/base.css"), "prefisso cartella aggiornato");
        assertFalse(html.contains("OFFERTE/base.css"));
    }

    @Test
    void renameToExistingTargetReturns409() throws Exception {
        String body = "{\"workspace\":\"" + ws + "\",\"from\":\"CLIENTE_A/preventivo.html\",\"to\":\"fattura.css\"}";
        mockMvc.perform(post("/workspace/rename").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void renameRejectsTraversalAndMissingSource() throws Exception {
        mockMvc.perform(post("/workspace/rename").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"from\":\"../fuga.html\",\"to\":\"x.html\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workspace/rename").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"from\":\"assente.html\",\"to\":\"x.html\"}"))
                .andExpect(status().isBadRequest());
    }

    // ===== Import DOCX con scaffold (difetto: template importato senza involucro HTML) =====

    @Test
    void importDocxProducesCompleteHtmlDocumentWithScaffold() throws Exception {
        // DOCX minimale costruito con POI (stessa libreria del servizio)
        byte[] docx;
        try (var bos = new java.io.ByteArrayOutputStream();
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var p = doc.createParagraph();
            p.createRun().setText("Titolo documento");
            doc.write(bos);
            docx = bos.toByteArray();
        }
        var part = new org.springframework.mock.web.MockMultipartFile(
                "file", "Atto Notarile.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/import-docx")
                        .file(part)
                        .param("workspace", ws.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("atto-notarile.html"));
        String html = Files.readString(snap("atto-notarile.html"));
        assertTrue(html.startsWith("<!DOCTYPE html>"), "scaffold: DOCTYPE presente");
        assertTrue(html.contains("charset=\"utf-8\""), "scaffold: charset presente");
        assertTrue(html.contains("<title>atto-notarile</title>"), "scaffold: title dal nome del DOCX");
        assertTrue(html.contains("<body>"), "scaffold: body presente");
        assertTrue(html.contains("Titolo documento"), "contenuto del DOCX preservato");
    }

    @Test
    void importDocxCreatesMissingDestinationFolder() throws Exception {
        byte[] docx;
        try (var bos = new java.io.ByteArrayOutputStream();
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var p = doc.createParagraph();
            p.createRun().setText("Carta intestata");
            doc.write(bos);
            docx = bos.toByteArray();
        }
        var part = new org.springframework.mock.web.MockMultipartFile(
                "file", "carta-intestata-alba.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/import-docx")
                        .file(part)
                        .param("workspace", ws.toString())
                        .param("folder", "ALBA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("ALBA/carta-intestata-alba.html"));
        assertTrue(Files.exists(snap("ALBA/carta-intestata-alba.html")),
                "la cartella di destinazione inesistente viene creata");
    }
}
