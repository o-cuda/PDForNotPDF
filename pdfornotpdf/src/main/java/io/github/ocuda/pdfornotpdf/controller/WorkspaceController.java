package io.github.ocuda.pdfornotpdf.controller;

import io.github.ocuda.pdfornotpdf.service.PdfService;
import io.github.ocuda.pdfornotpdf.service.WorkspaceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

@Controller
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final PdfService pdfService;

    public WorkspaceController(WorkspaceService workspaceService, PdfService pdfService) {
        this.workspaceService = workspaceService;
        this.pdfService = pdfService;
    }

    @GetMapping("/")
    public String loader() {
        return "loader";
    }

    @PostMapping("/workspace/load")
    public String loadWorkspace(@RequestParam String path, HttpSession session, Model model) {
        Path workspaceDir = Path.of(path);

        if (!workspaceService.isWorkspaceDirectory(workspaceDir)) {
            model.addAttribute("error", "Directory non valida: nessun template .html (anche in sottocartelle) in " + path);
            return "loader";
        }

        session.setAttribute("workspacePath", path);
        // PRG: redirect a una GET condivisibile — il refresh del browser non ripete il POST
        return "redirect:/workspace?ws=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    /**
     * Workbench. Il workspace arriva da query string (condivisibile/bookmarkabile)
     * con fallback alla sessione; viene ri-validato a ogni accesso.
     */
    @GetMapping("/workspace")
    public String workspace(@RequestParam(required = false) String ws, HttpSession session) {
        String path = ws != null ? ws : (String) session.getAttribute("workspacePath");
        if (path == null || !workspaceService.isWorkspaceDirectory(Path.of(path))) {
            return "redirect:/";
        }
        session.setAttribute("workspacePath", path);
        return "workspace";
    }

    /**
     * Albero ricorsivo dei file del workspace (per l'Explorer).
     */
    @GetMapping("/workspace/tree")
    @ResponseBody
    public WorkspaceService.TreeNode tree(@RequestParam String workspace) throws IOException {
        return workspaceService.buildTree(Path.of(workspace));
    }

    /**
     * Serve raw file content from workspace. Used by JS to load files into editors.
     */
    @GetMapping("/workspace/file")
    @ResponseBody
    public String getFile(@RequestParam String workspace, @RequestParam String file) throws IOException {
        Path dir = Path.of(workspace);
        return workspaceService.readFile(resolveInWorkspace(dir, file));
    }

    /**
     * Serve un'immagine del workspace per l'anteprima (src delle <img> riscritte dal server).
     */
    @GetMapping("/workspace/asset")
    @ResponseBody
    public ResponseEntity<?> getAsset(@RequestParam String workspace, @RequestParam String file) {
        try {
            byte[] image = workspaceService.readImage(Path.of(workspace), file);
            String mime = switch (file.toLowerCase()) {
                case String s when s.endsWith(".png") -> "image/png";
                case String s when s.endsWith(".jpg") || s.endsWith(".jpeg") -> "image/jpeg";
                case String s when s.endsWith(".gif") -> "image/gif";
                case String s when s.endsWith(".webp") -> "image/webp";
                case String s when s.endsWith(".svg") -> "image/svg+xml";
                default -> "application/octet-stream";
            };
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).body(image);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Immagine non servibile: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    public record SaveRequest(String workspace, String file, String content) {}

    /**
     * Salva il contenuto di un file del workspace (sovrascrive).
     */
    @PostMapping(value = "/workspace/file", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> saveFile(@RequestBody SaveRequest req) {
        try {
            workspaceService.saveFile(Path.of(req.workspace()), req.file(), req.content());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Salvataggio fallito: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Richiesta di render per preview e stampa. dirtyFiles = { percorso relativo → contenuto live }. */
    public record RenderRequest(String workspace, String template, Map<String, String> dirtyFiles) {}

    /** Risolve un percorso relativo impedendo path-traversal fuori dal workspace. */
    private Path resolveInWorkspace(Path dir, String relPath) {
        Path root = dir.toAbsolutePath().normalize();
        Path resolved = root.resolve(relPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Percorso fuori dal workspace: " + relPath);
        }
        return resolved;
    }

    /** Primo valore non blank, o null. */
    private String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /** Legge un file dal disco solo se il nome è stato fornito. */
    private String readIfSet(Path dir, String relPath) throws IOException {
        return (relPath != null && !relPath.isBlank()) ? workspaceService.readFile(resolveInWorkspace(dir, relPath)) : null;
    }

    /**
     * Render template preview. Supports both file-based and editor-based content.
     */
    @PostMapping(value = "/workspace/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String preview(@RequestBody RenderRequest req, HttpSession session, Model model) {
        String workspacePath = req.workspace() != null ? req.workspace() : (String) session.getAttribute("workspacePath");
        if (workspacePath == null) {
            model.addAttribute("error", "Nessun workspace caricato");
            return "fragments/preview";
        }

        Path dir = Path.of(workspacePath);
        try {
            model.addAttribute("previewHtml", renderCurrent(dir, req, WorkspaceService.AssetTarget.PREVIEW));
        } catch (Exception e) {
            model.addAttribute("error", "Errore: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        return "fragments/preview";
    }

    /**
     * Render del documento nello stato corrente: i file dirty (buffer non salvati) vincono sul disco,
     * che è il fallback. Include th:replace, CSS linkati e immagini vengono risolti dal service
     * (vedi WorkspaceService.renderDocument).
     */
    private String renderCurrent(Path dir, RenderRequest req, WorkspaceService.AssetTarget target) throws IOException {
        Map<String, String> overlay = req.dirtyFiles() != null ? req.dirtyFiles() : Collections.emptyMap();
        return workspaceService.renderDocument(dir, req.template(), overlay, target);
    }

    @PostMapping(value = "/workspace/print", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> printPdf(@RequestBody RenderRequest req, HttpSession session) throws IOException {
        String workspacePath = req.workspace() != null ? req.workspace() : (String) session.getAttribute("workspacePath");
        if (workspacePath == null) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Nessun workspace caricato".getBytes(StandardCharsets.UTF_8));
        }

        Path dir = Path.of(workspacePath);
        try {
            String html = renderCurrent(dir, req, WorkspaceService.AssetTarget.PRINT);

            String filename = req.template().replace(".html", "") + ".pdf";
            byte[] pdf = pdfService.generatePdf(html);

            ByteArrayResource resource = new ByteArrayResource(pdf);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(pdf.length)
                    .body(resource);
        } catch (Exception e) {
            // Errore pulito invece dello stack trace
            String msg = "Impossibile generare il PDF: " + e.getMessage();
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(msg.getBytes(StandardCharsets.UTF_8));
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(e.getMessage());
    }
}
