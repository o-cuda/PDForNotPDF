package io.github.ocuda.pdfornotpdf.controller;

import io.github.ocuda.pdfornotpdf.service.LlmService;
import io.github.ocuda.pdfornotpdf.service.ReleaseService;
import io.github.ocuda.pdfornotpdf.service.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Assistente LLM: propone modifiche ai template. Le proposte NON vengono mai
 * scritte su disco: il workbench le mostra come diff e l'utente le applica sul buffer.
 */
@RestController
@RequestMapping("/llm")
public class LlmController {

    private final LlmService llmService;
    private final ReleaseService releaseService;
    private final WorkspaceService workspaceService;

    public LlmController(LlmService llmService, ReleaseService releaseService, WorkspaceService workspaceService) {
        this.llmService = llmService;
        this.releaseService = releaseService;
        this.workspaceService = workspaceService;
    }

    public record AssistRequest(String workspace, String document, String instruction,
                                Map<String, String> dirtyFiles,
                                LlmService.LlmConfig llm) {}
    public record AssistStatus(boolean enabled, String model) {}

    @GetMapping("/status")
    public AssistStatus status() {
        return new AssistStatus(llmService.isEnabled(), llmService.getModel());
    }

    @PostMapping(value = "/assist", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> assist(@RequestBody AssistRequest req) throws IOException {
        if (!llmService.isEnabled()) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body("Assistente LLM non configurato: imposta app.llm.enabled/base-url/model "
                            + "(endpoint OpenAI-compatibile o Ollama self-hosted).".getBytes(StandardCharsets.UTF_8));
        }
        Path ws = Path.of(req.workspace());
        try {
            Set<String> closure = releaseService.resolveClosure(ws, req.document(), req.dirtyFiles());
            List<Map<String, String>> files = new ArrayList<>();
            for (String rel : closure) {
                String kind = WorkspaceService.kindOf(rel);
                if (!"html".equals(kind) && !"css".equals(kind) && !"json".equals(kind)) continue;
                String content = req.dirtyFiles() != null && req.dirtyFiles().containsKey(rel)
                        ? req.dirtyFiles().get(rel)
                        : (Files.exists(ws.resolve(rel)) ? Files.readString(ws.resolve(rel)) : null);
                if (content != null) {
                    files.add(Map.of("path", rel, "content", content));
                }
            }
            LlmService.AssistResult result = llmService.assist(req.instruction(), files, req.llm());
            Map<String, Object> out = new HashMap<>();
            out.put("spiegazione", result.spiegazione());
            out.put("modifiche", result.modifiche());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(out).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Assistente fallito: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
