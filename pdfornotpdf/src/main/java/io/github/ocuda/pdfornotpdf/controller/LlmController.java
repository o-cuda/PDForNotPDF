package io.github.ocuda.pdfornotpdf.controller;

import io.github.ocuda.pdfornotpdf.service.LlmService;
import io.github.ocuda.pdfornotpdf.web.ValidationException;
import io.github.ocuda.pdfornotpdf.service.ReleaseService;
import io.github.ocuda.pdfornotpdf.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Assistente LLM", description = "Proposte di modifica ai template (BYOK/self-hosted)")
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

    @Operation(summary = "Stato dell'assistente (abilitato + modello)")
    @GetMapping("/status")
    public AssistStatus status() {
        return new AssistStatus(llmService.isEnabled(), llmService.getModel());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Config LLM valida fornita dal browser (credenziali utente): base-url + modello richiesti. */
    private static boolean hasUserConfig(LlmService.LlmConfig cfg) {
        return cfg != null && notBlank(cfg.baseUrl()) && notBlank(cfg.model());
    }

    @Operation(summary = "Proponde modifiche ai template (mai scritte su disco)")
    @PostMapping(value = "/assist", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> assist(@RequestBody AssistRequest req) throws IOException {
        // ok se c'è la config server OPPURE credenziali valide per-request dal browser
        if (!llmService.isEnabled() && !hasUserConfig(req.llm())) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Assistente LLM non configurato: imposta app.llm.enabled/base-url/model "
                            + "(endpoint OpenAI-compatibile o Ollama self-hosted), "
                            + "oppure compila Base URL/Modello nel pannello Assistente.")
                            .getBytes(StandardCharsets.UTF_8));
        }
        Path ws = Path.of(req.workspace());
        Set<String> closure = releaseService.resolveClosure(ws, req.document(), req.dirtyFiles());
        // B2 (audit 05): il contesto legge dalla RADICE SNAPSHOT — prima usava ws/ e i file
        // non-dirty non arrivavano mai al modello
        List<Map<String, String>> files =
                llmService.buildContext(workspaceService.snapshotRoot(ws), closure, req.dirtyFiles());
        LlmService.AssistResult result;
        try {
            result = llmService.assist(req.instruction(), files, req.llm());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidationException("Assistente interrotto");
        }
        Map<String, Object> out = new HashMap<>();
        out.put("spiegazione", result.spiegazione());
        out.put("modifiche", result.modifiche());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(out).getBytes(StandardCharsets.UTF_8));
    }
}
