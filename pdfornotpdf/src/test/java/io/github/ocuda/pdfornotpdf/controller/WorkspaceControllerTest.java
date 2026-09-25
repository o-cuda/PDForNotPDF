package io.github.ocuda.pdfornotpdf.controller;

import io.github.ocuda.pdfornotpdf.service.WorkspacePaths;

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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
        // con B4 il salvataggio richiede il file esistente (la creazione passa da /workspace/node)
        Files.writeString(snap("CLIENTE_A/nuovo.html"), "");
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

    // ===== Upload immagini: guardie traversal/segmenti nascosti (S5/R6, T3) =====

    @Test
    void uploadImageRejectsTraversalAndHiddenFolders() throws Exception {
        var part = new org.springframework.mock.web.MockMultipartFile("file", "fuga.png", "image/png", new byte[]{1, 2, 3});

        // traversal fuori dallo snapshot
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/upload-image").file(part)
                        .param("workspace", ws.toString()).param("folder", "../evil"))
                .andExpect(status().isBadRequest());
        // segmento nascosto intermedio (prima passava: S5)
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/upload-image").file(part)
                        .param("workspace", ws.toString()).param("folder", "assets/.git"))
                .andExpect(status().isBadRequest());
        // segmento dot intermedio
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/upload-image").file(part)
                        .param("workspace", ws.toString()).param("folder", "a/./b"))
                .andExpect(status().isBadRequest());
        // nulla deve essere scritto
        assertFalse(Files.exists(snap("../evil/fuga.png")));
        assertFalse(Files.exists(snap("assets/.git/fuga.png")));
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

    // ===== Delete: path traversal (T4) =====

    @Test
    void deleteRejectsPathTraversal() throws Exception {
        mockMvc.perform(post("/workspace/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"../fuga.html\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workspace/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"path\":\"a/../../fuga.html\"}"))
                .andExpect(status().isBadRequest());
        assertFalse(Files.exists(ws.resolve("fuga.html")), "nulla scritto/cancellato fuori dallo snapshot");
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
        // con le eccezioni tipizzate (R2) una sorgente inesistente è una 404, non una 400 generica
        mockMvc.perform(post("/workspace/rename").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"from\":\"assente.html\",\"to\":\"x.html\"}"))
                .andExpect(status().isNotFound());
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
        // PNG minimale valido (1x1) per verificare che gli asset seguano la cartella
        byte[] png;
        var img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (var bos = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(img, "png", bos);
            png = bos.toByteArray();
        }
        byte[] docx;
        try (var bos = new java.io.ByteArrayOutputStream();
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var p = doc.createParagraph();
            p.createRun().setText("Carta intestata");
            p.createRun().addPicture(new java.io.ByteArrayInputStream(png),
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "logo.png",
                    org.apache.poi.util.Units.toEMU(20), org.apache.poi.util.Units.toEMU(20));
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
                .andExpect(jsonPath("$.template").value("ALBA/carta-intestata-alba.html"))
                .andExpect(jsonPath("$.assets[0]").value(
                        org.hamcrest.Matchers.startsWith("ALBA/assets/import/carta-intestata-alba/")));
        assertTrue(Files.exists(snap("ALBA/carta-intestata-alba.html")),
                "la cartella di destinazione inesistente viene creata");
        assertTrue(Files.exists(snap("ALBA/assets/import/carta-intestata-alba")),
                "gli asset finiscono sotto la cartella cliente, non nella root");
        String html = Files.readString(snap("ALBA/carta-intestata-alba.html"));
        assertTrue(html.contains("src=\"ALBA/assets/import/carta-intestata-alba/"),
                "gli <img src> puntano agli asset dentro la cartella");
    }

    // ===== Re-import DOCX (B3, T6): sovrascrittura solo con conferma =====

    @Test
    void importDocxReimportRequiresOverwriteConfirmation() throws Exception {
        byte[] docx;
        try (var bos = new java.io.ByteArrayOutputStream();
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            doc.createParagraph().createRun().setText("Prima versione");
            doc.write(bos);
            docx = bos.toByteArray();
        }
        var part = new org.springframework.mock.web.MockMultipartFile("file", "Atto.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
        var mpb = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/workspace/import-docx")
                .file(part).param("workspace", ws.toString());

        // prima import → 200
        mockMvc.perform(mpb).andExpect(status().isOk());
        String first = Files.readString(snap("atto.html"));
        Path assetDir = snap("assets/import/atto");
        long assetPrima = java.nio.file.Files.exists(assetDir)
                ? java.util.stream.Stream.of(assetDir.toFile()).flatMap(d -> java.util.Arrays.stream(d.listFiles())).count() : 0;
        // re-import senza conferma → 409 (prima sovrascriveva silenziosamente)
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/import-docx")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "Atto.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx))
                        .param("workspace", ws.toString()))
                .andExpect(status().isConflict());
        assertEquals(first, Files.readString(snap("atto.html")), "senza overwrite il file non è toccato");
        long assetDopo = java.nio.file.Files.exists(assetDir)
                ? java.util.Arrays.stream(assetDir.toFile().listFiles()).count() : 0;
        assertEquals(assetPrima, assetDopo, "B3: il 409 non crea né tocca asset (nessun orfano)");
        // re-import con overwrite=true → 200 e contenuto aggiornato
        try (var bos = new java.io.ByteArrayOutputStream();
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            doc.createParagraph().createRun().setText("Seconda versione");
            doc.write(bos);
            docx = bos.toByteArray();
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/import-docx")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "Atto.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx))
                        .param("workspace", ws.toString())
                        .param("overwrite", "true"))
                .andExpect(status().isOk());
        assertTrue(Files.readString(snap("atto.html")).contains("Seconda versione"), "sovrascritto con la nuova versione");
    }

    // ===== Release-asset HTTP (T2): mime, non-immagine, traversal =====

    @Test
    void releaseAssetServesOnlyImagesInsideRelease() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        Files.createDirectories(snap("x"));
        Path rel = ws.resolve("release/CLIENTE_A/fattura/v1/files/assets/logo.png");
        Files.createDirectories(rel.getParent());
        Files.write(rel, png);
        Path relHtml = ws.resolve("release/CLIENTE_A/fattura/v1/files/CLIENTE_A/fattura.html");
        Files.createDirectories(relHtml.getParent());
        Files.writeString(relHtml, "<html></html>");

        // immagine valida → 200 image/png
        mockMvc.perform(get("/workspace/release-asset")
                        .param("workspace", ws.toString())
                        .param("path", "CLIENTE_A/fattura/v1/files/assets/logo.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.valueOf("image/png")))
                .andExpect(content().bytes(png));
        // non-immagine → 400
        mockMvc.perform(get("/workspace/release-asset")
                        .param("workspace", ws.toString())
                        .param("path", "CLIENTE_A/fattura/v1/files/CLIENTE_A/fattura.html"))
                .andExpect(status().isBadRequest());
        // traversal fuori dalla release → 400
        mockMvc.perform(get("/workspace/release-asset")
                        .param("workspace", ws.toString())
                        .param("path", "CLIENTE_A/fattura/v1/files/../../../segreto.png"))
                .andExpect(status().isBadRequest());
    }

    // ===== Fase 4: candidates endpoint, S3 (CSP su asset), B4 (save su file sparito) =====

    @Test
    void candidatesReturnsEmptyListWithoutGitRepo() throws Exception {
        mockMvc.perform(get("/release/candidates").param("workspace", ws.toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void assetServesWithCspSandboxHeader() throws Exception {
        mockMvc.perform(get("/workspace/asset")
                        .param("workspace", ws.toString())
                        .param("file", "assets/logo.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void saveToVanishedFileReturns409NotSilentRecreate() throws Exception {
        mockMvc.perform(post("/workspace/file").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"file\":\"CLIENTE_A/sparito.html\",\"content\":\"x\"}"))
                .andExpect(status().isConflict());
        assertFalse(Files.exists(snap("CLIENTE_A/sparito.html")),
                "un file cancellato da un altro utente non viene resuscitato dal salvataggio (B4)");
    }

    @Test
    void optimisticLockingNowActuallyFiresOnDiskConflict() throws Exception {
        // FIX fase 4: i pre-check risolvevano contro la root (mai attivi) invece che contro snapshot/
        // WP6/P1: il confronto viaggia come sha256, non come intero contenuto
        Files.writeString(snap("CLIENTE_A/conflitto.html"), "versione-su-disco");
        String hashSuDisco = WorkspacePaths.sha256Hex("versione-su-disco".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/workspace/file").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"file\":\"CLIENTE_A/conflitto.html\","
                                + "\"content\":\"mia versione\",\"expectedSha256\":\"abcd\"}"))
                .andExpect(status().isConflict());
        assertEquals("versione-su-disco", Files.readString(snap("CLIENTE_A/conflitto.html")),
                "il disco non viene sovrascritto in conflitto");
        // e con l'hash corretto il salvataggio passa
        mockMvc.perform(post("/workspace/file").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"file\":\"CLIENTE_A/conflitto.html\","
                                + "\"content\":\"mia versione\",\"expectedSha256\":\"" + hashSuDisco + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void saveForceBypassesSha256Check() throws Exception {
        // T20 (WP6): senza expectedSha256 (force dalla UI dopo conferma) si sovrascrive comunque
        Files.writeString(snap("CLIENTE_A/force.html"), "vecchio");
        mockMvc.perform(post("/workspace/file").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"file\":\"CLIENTE_A/force.html\",\"content\":\"nuovo\"}"))
                .andExpect(status().isOk());
        assertEquals("nuovo", Files.readString(snap("CLIENTE_A/force.html")));
    }

    // ===== Audit 05: T12 (folder traversal in import-docx) + T17 (document traversal) + B6 =====

    @Test
    void importDocxRejectsFolderTraversalAndWritesNothing() throws Exception {
        byte[] docx;
        try (var bos = new java.io.ByteArrayOutputStream();
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            doc.createParagraph().createRun().setText("traversal");
            doc.write(bos);
            docx = bos.toByteArray();
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/import-docx")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "Atto.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx))
                        .param("workspace", ws.toString())
                        .param("folder", "../../../pwn"))
                .andExpect(status().isBadRequest());
        assertFalse(Files.exists(ws.resolve("../pwn")), "nulla scritto fuori dal workspace (C2)");
        assertFalse(Files.exists(snap("../pwn")));
    }

    @Test
    void listAndActiveFilesRejectDocumentTraversal() throws Exception {
        // T17 (audit 05 / B5): document arriva da request — deve restare dentro release/
        mockMvc.perform(get("/release/list")
                        .param("workspace", ws.toString())
                        .param("document", "../snapshot/CLIENTE_A"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/release/list")
                        .param("workspace", ws.toString())
                        .param("document", "../../etc"))
                .andExpect(status().isBadRequest());
    }


    // ===== Audit 05: T15 (import-reference, endpoint senza copertura) + T16 (release preview/print HTTP) =====

    @Test
    void importReferenceSavesPdfAndSanitizesName() throws Exception {
        var part = new org.springframework.mock.web.MockMultipartFile("file", "Capitolo 1 (bozza).pdf",
                "application/pdf", "%PDF-1.4 riferimento".getBytes());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/import-reference")
                        .file(part)
                        .param("workspace", ws.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("assets/reference/Capitolo_1__bozza_.pdf"));
        assertTrue(Files.exists(snap("assets/reference/Capitolo_1__bozza_.pdf")));
    }

    @Test
    void importReferenceRejectsInvalidNames() throws Exception {
        // B6: il nome ".." sopravviveva alla sanitizzazione charset
        var part = new org.springframework.mock.web.MockMultipartFile("file", "..",
                "application/pdf", "%PDF".getBytes());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/workspace/import-reference")
                        .file(part)
                        .param("workspace", ws.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void releasePrintAndServeViaHttp() throws Exception {
        // T16 (audit 05): /release/print e /release/preview al livello HTTP
        Path docFiles = ws.resolve("release/DEMO/atto/v1/files");
        Files.createDirectories(docFiles.resolve("DEMO"));
        Files.writeString(docFiles.resolve("DEMO/atto.html"),
                "<html><head><title>t</title></head><body><h1>DEMO PRINT</h1></body></html>");
        Files.writeString(ws.resolve("release/DEMO/atto/index.json"),
                "{\"document\":\"DEMO/atto\",\"active\":1,\"versions\":[{\"version\":1,"
                        + "\"status\":\"approved\",\"effectiveFrom\":\"2020-01-01\"}]}");

        byte[] pdf = mockMvc.perform(post("/release/print").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"document\":\"DEMO/atto\",\"version\":1,\"template\":\"DEMO/atto.html\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(pdf.length > 500, "PDF di stampa della release");

        mockMvc.perform(post("/release/preview?version=1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"" + ws + "\",\"template\":\"DEMO/atto.html\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DEMO PRINT")));
    }

    // ===== Robustezza B1: cartelle illeggibili non producono 500 (T5) =====

    @Test
    void loadWorkspaceWithUnreadableSubdirectoryStillLoads() throws Exception {
        Path locked = snap("lockdir");
        Files.createDirectories(locked);
        Files.writeString(snap("doc.html"), "<html><body>doc</body></html>");
        assumeTrue(locked.toFile().setReadable(false) && locked.toFile().setExecutable(false),
                "permessi POSIX non modificabili (utente root?)");
        try {
            mockMvc.perform(post("/workspace/load").param("path", ws.toString()))
                    .andExpect(status().is3xxRedirection());
            mockMvc.perform(get("/workspace/tree").param("workspace", ws.toString()))
                    .andExpect(status().isOk());
        } finally {
            locked.toFile().setReadable(true);
            locked.toFile().setExecutable(true);
        }
    }

    @Test
    void loaderOnDirectoryWithUnreadableContentShowsCleanErrorNot500() throws Exception {
        Path other = Files.createTempDirectory("b1-loader");
        Path locked = other.resolve("hidden");
        Files.createDirectories(locked);
        assumeTrue(locked.toFile().setReadable(false) && locked.toFile().setExecutable(false),
                "permessi POSIX non modificabili (utente root?)");
        try {
            // atteso: messaggio pulito di loader (200), non 500
            mockMvc.perform(post("/workspace/load").param("path", other.toString()))
                    .andExpect(status().isOk());
        } finally {
            locked.toFile().setReadable(true);
            locked.toFile().setExecutable(true);
            org.assertj.core.util.Files.delete(other.toFile());
        }
    }
}
