package io.github.ocuda.pdfornotpdf.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test unitari dell'assistente LLM (audit 03 / T9): parsing delle proposte, tolleranza ai
 * fence markdown, alias di campo e validazione del base URL (audit 03 / S4).
 * Nessuna rete: la validazione e il parsing sono puri.
 */
class LlmServiceTest {

    private final LlmService service = new LlmService();

    // ===== parseProposal (T9) =====

    @Test
    void parseProposalReadsPlainJson() throws IOException {
        String json = """
                {"spiegazione": "Cambiato il titolo",
                 "modifiche": [{"path": "CLIENTE_A/fattura.html", "contenuto": "<html>nuovo</html>"}]}
                """;
        LlmService.AssistResult r = LlmService.parseProposal(json);
        assertEquals("Cambiato il titolo", r.spiegazione());
        assertEquals(1, r.modifiche().size());
        assertEquals("CLIENTE_A/fattura.html", r.modifiche().get(0).path());
        assertEquals("<html>nuovo</html>", r.modifiche().get(0).contenuto());
    }

    @Test
    void parseProposalToleratesMarkdownFenceAndSurroundingText() throws IOException {
        String content = "Ecco la proposta:\n```json\n"
                + "{\"spiegazione\":\"ok\",\"modifiche\":[{\"path\":\"a.html\",\"contenuto\":\"x\"}]}\n"
                + "```\nFatto.";
        LlmService.AssistResult r = LlmService.parseProposal(content);
        assertEquals(1, r.modifiche().size());
        assertEquals("a.html", r.modifiche().get(0).path());
    }

    @Test
    void parseProposalAcceptsEnglishAliases() throws IOException {
        String json = """
                {"explanation": "English explanation",
                 "edits": [{"path": "b.css", "content": "h1{color:red}"}]}
                """;
        LlmService.AssistResult r = LlmService.parseProposal(json);
        assertEquals("English explanation", r.spiegazione());
        assertEquals("h1{color:red}", r.modifiche().get(0).contenuto());
    }

    @Test
    void parseProposalRejectsTextWithoutJson() {
        assertThrows(IOException.class, () -> LlmService.parseProposal("nessun json qui"));
        assertThrows(IOException.class, () -> LlmService.parseProposal(null));
    }

    @Test
    void parseProposalRejectsIncompleteJsonObject() {
        // il primo '{' non viene mai chiuso in modo bilanciato (la '}' è dentro una stringa)
        assertThrows(IOException.class, () -> LlmService.parseProposal("{\"spiegazione\": \"aperta..."));
    }

    @Test
    void parseProposalRejectsResponseWithoutModifiche() {
        assertThrows(IOException.class, () -> LlmService.parseProposal("{\"spiegazione\":\"solo testo\"}"));
    }

    // ===== validazione base URL (S4) =====

    @Test
    void validatedBaseUrlAcceptsHttpAndHttps() {
        assertEquals("http://localhost:11434/v1", LlmService.validatedBaseUrl("http://localhost:11434/v1/"));
        assertEquals("https://openrouter.ai/api/v1", LlmService.validatedBaseUrl("https://openrouter.ai/api/v1"));
    }

    @Test
    void validatedBaseUrlRejectsNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class, () -> LlmService.validatedBaseUrl("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> LlmService.validatedBaseUrl("ftp://interno/"));
        assertThrows(IllegalArgumentException.class, () -> LlmService.validatedBaseUrl("localhost:11434"));
        assertThrows(IllegalArgumentException.class, () -> LlmService.validatedBaseUrl(""));
    }

    // ===== B2: il contesto legge i file non-dirty dalla radice snapshot =====

    @Test
    void buildContextReadsNonDirtyFilesFromSnapshotRoot(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve("snapshot/CLIENTE_A"));
        Files.writeString(ws.resolve("snapshot/CLIENTE_A/fattura.html"), "<html>dal-disco</html>");
        Files.writeString(ws.resolve("snapshot/CLIENTE_A/fattura.json"), "{\"titolo\":\"dal-disco\"}");
        Files.write(ws.resolve("snapshot/CLIENTE_A/logo.png"), new byte[]{1});

        var closure = java.util.Set.of("CLIENTE_A/fattura.html", "CLIENTE_A/fattura.json", "CLIENTE_A/logo.png");
        var dirty = Map.of("CLIENTE_A/fattura.json", "{\"titolo\":\"dirty\"}");

        var ctx = service.buildContext(ws.resolve("snapshot"), closure, dirty);

        assertTrue(ctx.stream().anyMatch(f -> "CLIENTE_A/fattura.html".equals(f.get("path"))
                && f.get("content").contains("dal-disco")), "B2: il file non-dirty viene letto da snapshot/");
        assertTrue(ctx.stream().anyMatch(f -> "CLIENTE_A/fattura.json".equals(f.get("path"))
                && f.get("content").contains("dirty")), "il dirty vince sul disco");
        assertFalse(ctx.stream().anyMatch(f -> "CLIENTE_A/logo.png".equals(f.get("path"))),
                "le immagini non entrano nel contesto");
    }

    // ===== pick per-richiesta =====

    @Test
    void pickPrefersPerRequestValueOverFallback() throws Exception {
        // pick è esercitato indirettamente: base URL non valido nella config per-request deve
        // fallire PRIMA di qualsiasi chiamata di rete (audit 03 / S4)
        var cfg = new LlmService.LlmConfig("gopher:// interno", "key", "modello");
        assertThrows(IllegalArgumentException.class,
                () -> service.assist("istruzione", List.of(), cfg));
    }
}
