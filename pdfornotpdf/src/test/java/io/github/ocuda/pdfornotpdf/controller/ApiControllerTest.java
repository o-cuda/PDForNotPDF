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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test HTTP della REST API di generazione PDF.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {

    @Autowired
    MockMvc mockMvc;

    @TempDir
    Path ws;

    /** Percorso dentro l'area di lavoro (snapshot/). */
    private Path snap(String rel) { return ws.resolve("snapshot").resolve(rel); }

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(ws.resolve("snapshot"));
        Files.writeString(snap("fattura.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <body>
                  <div th:replace="~{includes/header :: header}"></div>
                  <h1 th:text="${titolo}">Titolo</h1>
                </body>
                </html>
                """);
        Files.writeString(snap("fattura.css"), "h1 { color: blue; }");
        Files.createDirectories(snap("includes"));
        Files.writeString(snap("includes/header.html"), "<div th:fragment=\"header\">HEADER</div>");
    }

    @Test
    void generateReturnsPdfWithCssAndIncludes() throws Exception {
        byte[] pdf = mockMvc.perform(post("/api/generate")
                        .param("workspace", ws.toString())
                        .param("template", "fattura.html")
                        .param("cssFile", "fattura.css")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titolo\":\"Da API\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("fattura.pdf")))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(pdf.length > 500);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void generateWithoutOptionalParamsStillWorks() throws Exception {
        mockMvc.perform(post("/api/generate")
                        .param("workspace", ws.toString())
                        .param("template", "fattura.html")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void generateWithMissingTemplateFails() throws Exception {
        mockMvc.perform(post("/api/generate")
                        .param("workspace", ws.toString())
                        .param("template", "assente.html")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
