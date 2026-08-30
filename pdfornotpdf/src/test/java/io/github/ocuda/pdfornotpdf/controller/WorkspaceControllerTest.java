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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @TempDir
    Path ws;

    @BeforeEach
    void setUp() throws IOException {
        // Layout multi-tenant minimale
        Files.createDirectories(ws.resolve("STANDARD/include"));
        Files.createDirectories(ws.resolve("CLIENTE_A/include"));
        Files.createDirectories(ws.resolve("assets"));

        Files.writeString(ws.resolve("STANDARD/tabella-tariffe.html"),
                "<table th:fragment=\"tariffe\"><tr><td>TARIFFE</td></tr></table>");
        Files.writeString(ws.resolve("STANDARD/include/common.css"), "body { color: #111; }\n");
        Files.writeString(ws.resolve("CLIENTE_A/include/common.css"), "h1 { color: #dc2626; }\n");
        Files.write(ws.resolve("assets/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        Files.writeString(ws.resolve("CLIENTE_A/preventivo.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head><link rel="stylesheet" href="STANDARD/include/common.css" />
                <link rel="stylesheet" href="CLIENTE_A/include/common.css" /></head>
                <body><h1 th:text="${titolo}">Titolo</h1></body>
                </html>
                """);
        Files.writeString(ws.resolve("CLIENTE_A/preventivo.json"), "{\"titolo\":\"Da disco\"}");
        Files.writeString(ws.resolve("fattura.css"), "h1 { color: red; }");
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

        assertEquals("ciao", Files.readString(ws.resolve("CLIENTE_A/nuovo.html")));
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
}
