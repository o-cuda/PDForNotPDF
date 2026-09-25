package io.github.ocuda.pdfornotpdf.controller;

import io.github.ocuda.pdfornotpdf.service.PdfService;
import io.github.ocuda.pdfornotpdf.service.ReleaseService;
import io.github.ocuda.pdfornotpdf.service.WorkspacePaths;
import io.github.ocuda.pdfornotpdf.service.WorkspaceService;
import io.github.ocuda.pdfornotpdf.web.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

@RestController
@Tag(name = "Generazione PDF", description = "API programmatica per gestionali esterni")
@RequestMapping("/api")
public class ApiController {

    private final WorkspaceService workspaceService;
    private final PdfService pdfService;
    private final ReleaseService releaseService;

    public ApiController(WorkspaceService workspaceService, PdfService pdfService, ReleaseService releaseService) {
        this.workspaceService = workspaceService;
        this.pdfService = pdfService;
        this.releaseService = releaseService;
    }

    @Operation(summary = "Genera un PDF da template + dati (JSON), snapshot pubblicato se presente")
    @PostMapping("/generate")
    public ResponseEntity<?> generatePdf(
            @RequestParam String workspace,
            @RequestParam String template,
            @RequestParam(required = false) Integer version,
            @RequestParam(required = false) String cssFile,
            @RequestBody(required = false) Map<String, Object> data) {

        try {
            Path dir = Path.of(workspace);
            String jsonData = data != null ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data) : "{}";

            // Se il documento è stato pubblicato, si serve lo SNAPSHOT (versione pinnata o attiva approvata);
            // altrimenti fallback sul workspace (compatibilità con documenti mai pubblicati).
            String document = template.replaceAll("(?i)\\.html$", "");
            Path releaseFiles = releaseService.activeFilesDir(dir, document, version);
            if (version != null && releaseFiles == null) {
                return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                        .body(("Versione non trovata: " + document + " v" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            String html;
            if (releaseFiles != null) {
                html = workspaceService.renderTemplate(releaseFiles, template, jsonData);
                html = workspaceService.inlineLinkedAssets(html, releaseFiles, Map.of(), WorkspaceService.AssetTarget.PRINT);
            } else {
                html = workspaceService.renderDocument(dir, template, Map.of(), WorkspaceService.AssetTarget.PRINT);
            }
            String css = (cssFile != null && !cssFile.isBlank())
                    ? WorkspacePaths.readTextInside(workspaceService.snapshotRoot(dir), cssFile)
                    : null;
            html = workspaceService.injectCss(html, css);

            byte[] pdf = pdfService.generatePdf(html);

            String filename = template.replace(".html", "") + ".pdf";
            ByteArrayResource resource = new ByteArrayResource(pdf);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(pdf.length)
                    .body(resource);
        } catch (Exception e) {
            // contratto di stampa: 400 con messaggio presentabile; lo stacktrace va nei log (R7)
            throw new ValidationException("Impossibile generare il PDF: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }
}
