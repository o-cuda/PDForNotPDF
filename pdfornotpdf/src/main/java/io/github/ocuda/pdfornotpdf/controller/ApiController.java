package io.github.ocuda.pdfornotpdf.controller;

import io.github.ocuda.pdfornotpdf.service.PdfService;
import io.github.ocuda.pdfornotpdf.service.WorkspaceService;
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
@RequestMapping("/api")
public class ApiController {

    private final WorkspaceService workspaceService;
    private final PdfService pdfService;

    public ApiController(WorkspaceService workspaceService, PdfService pdfService) {
        this.workspaceService = workspaceService;
        this.pdfService = pdfService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generatePdf(
            @RequestParam String workspace,
            @RequestParam String template,
            @RequestParam(required = false) String cssFile,
            @RequestBody(required = false) Map<String, Object> data) throws IOException {

        try {
            Path dir = Path.of(workspace);
            String jsonData = data != null ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data) : "{}";

            // Render completo (include, CSS linkati, immagini) + eventuale CSS extra da parametro
            String html = workspaceService.renderDocument(dir, template, Map.of(), WorkspaceService.AssetTarget.PRINT);
            String css = (cssFile != null && !cssFile.isBlank())
                    ? workspaceService.readFile(dir.resolve(cssFile))
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
            // Errore pulito invece dello stack trace (es. template inesistente)
            String msg = "Impossibile generare il PDF: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(msg.getBytes(StandardCharsets.UTF_8));
        }
    }
}
