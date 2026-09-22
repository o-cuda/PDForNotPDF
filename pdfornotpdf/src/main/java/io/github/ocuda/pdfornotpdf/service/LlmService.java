package io.github.ocuda.pdfornotpdf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assistente LLM per la modifica dei template.
 *
 * Architettura: endpoint OpenAI-compatibile (OpenAI, OpenRouter, gateway aziendali,
 * Ollama self-hosted…) + chiave in configurazione. Nessun dato lascia l'infrastruttura
 * se si usa un modello self-hosted.
 *
 * Il modello NON scrive mai su disco: restituisce PROPOSTE di modifica che il workbench
 * mostra come diff e l'utente applica sul buffer (poi preview + salvataggio umano).
 */
@Service
public class LlmService {

    @Value("${app.llm.enabled:false}")
    private boolean enabled;
    @Value("${app.llm.base-url:}")
    private String baseUrl;
    @Value("${app.llm.api-key:}")
    private String apiKey;
    @Value("${app.llm.model:}")
    private String model;

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record FileEdit(String path, String contenuto) {}
    public record AssistResult(String spiegazione, List<FileEdit> modifiche) {}

    public boolean isEnabled() {
        return enabled && baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
    }

    public String getModel() {
        return model;
    }

    /** Configurazione per-richiesta (credenziali utente dal browser). */
    public record LlmConfig(String baseUrl, String apiKey, String model) {}

    private static String pick(String perRequest, String fallback) {
        return (perRequest != null && !perRequest.isBlank()) ? perRequest : fallback;
    }

    /** contesto: [{path, content}] dei file della chiusura del documento (overlay-first). */
    public AssistResult assist(String instruction, List<Map<String, String>> files, LlmConfig override)
            throws IOException, InterruptedException {
        String bu = pick(override != null ? override.baseUrl() : null, baseUrl);
        String key = pick(override != null ? override.apiKey() : null, apiKey);
        String mdl = pick(override != null ? override.model() : null, model);
        if (bu == null || bu.isBlank() || mdl == null || mdl.isBlank())
            throw new IllegalStateException("Assistente LLM non configurato (endpoint e modello richiesti)");

        StringBuilder ctx = new StringBuilder();
        for (Map<String, String> f : files) {
            ctx.append("=== FILE: ").append(f.get("path")).append(" ===\n")
               .append(f.get("content")).append("\n\n");
        }

        String system = """
                Sei un assistente esperto di template Thymeleaf per la generazione di PDF \
                (HTML + CSS + dati JSON mock in italiano).
                Ricevi i file correnti di un documento e una richiesta di modifica.
                Rispondi SOLO con un oggetto JSON valido, nessun testo fuori dal JSON, \
                con questa forma esatta:
                {"spiegazione": "breve spiegazione in italiano delle modifiche", \
                "modifiche": [{"path": "percorso/relativo/come/fornito", \
                "contenuto": "contenuto COMPLETO del file modificato"}]}
                Regole:
                - i "path" devono essere identici a quelli dei file forniti
                - includi solo i file che modifichi, con il contenuto COMPLETO del file
                - conserva i th:* esistenti, i <link> ai CSS e le immagini; non rimuovere funzionalità
                - non cambiare i dati già presenti nel JSON a meno che non sia la richiesta
                """;
        String user = "FILE CORRENTI:\n\n" + ctx + "\nRICHIESTA:\n" + instruction;

        Map<String, Object> body = new HashMap<>();
        body.put("model", mdl);
        body.put("temperature", 0.2);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(stripTrailingSlash(bu) + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)));
        if (key != null && !key.isBlank()) rb.header("Authorization", "Bearer " + key);

        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IOException("LLM HTTP " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(300, resp.body().length())));
        }
        String content = om.readTree(resp.body())
                .path("choices").get(0).path("message").path("content").asText();
        return parseProposal(content);
    }

    /** Estrae il JSON della proposta dal testo del modello (tollera fence ```json e testo attorno). */
    static AssistResult parseProposal(String content) throws IOException {
        String text = content == null ? "" : content.trim();
        // rimuovi fence markdown se presenti
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(json)?\\s*", "");
            int end = text.lastIndexOf("```");
            if (end != -1) text = text.substring(0, end);
            text = text.trim();
        }
        // isola il primo oggetto JSON bilanciato
        int start = text.indexOf('{');
        if (start == -1) throw new IOException("La risposta del modello non contiene JSON");
        int depth = 0;
        boolean inString = false;
        int end = -1;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
            }
        }
        if (end == -1) throw new IOException("La risposta del modello non contiene un oggetto JSON completo");

        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(text.substring(start, end));
        String spiegazione = root.path("spiegazione").asText(
                root.path("explanation").asText("Modifica proposta dall'assistente"));
        List<FileEdit> modifiche = new ArrayList<>();
        JsonNode mods = root.withArray("modifiche");
        if (mods.isMissingNode()) mods = root.withArray("edits");
        for (JsonNode m : mods) {
            String path = m.path("path").asText(null);
            String contenuto = m.has("contenuto") ? m.path("contenuto").asText()
                    : (m.has("content") ? m.path("content").asText() : null);
            if (path != null && contenuto != null) {
                modifiche.add(new FileEdit(path, contenuto));
            }
        }
        if (modifiche.isEmpty()) throw new IOException("La risposta del modello non contiene modifiche");
        return new AssistResult(spiegazione, modifiche);
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // utf8 helper per eventuale uso futuro
    @SuppressWarnings("unused")
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
