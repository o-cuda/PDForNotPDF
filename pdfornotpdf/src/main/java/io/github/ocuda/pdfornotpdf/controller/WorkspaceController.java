package io.github.ocuda.pdfornotpdf.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ocuda.pdfornotpdf.service.DocxImportService;
import io.github.ocuda.pdfornotpdf.service.PdfService;
import io.github.ocuda.pdfornotpdf.service.ReleaseService;
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
import java.io.FileNotFoundException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Map;

@Controller
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final PdfService pdfService;
    private final ReleaseService releaseService;
    private final DocxImportService docxImportService;

    public WorkspaceController(WorkspaceService workspaceService, PdfService pdfService,
                               ReleaseService releaseService, DocxImportService docxImportService) {
        this.workspaceService = workspaceService;
        this.pdfService = pdfService;
        this.releaseService = releaseService;
        this.docxImportService = docxImportService;
    }

    public record ImportResultResponse(String template, List<String> assets) {}
    public record ReferenceResponse(String path, String url) {}

    /** Import DOCX → template HTML di partenza + immagini estratte in assets/import/. */
    @PostMapping(value = "/workspace/import-docx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<?> importDocx(@RequestParam String workspace,
                                        @RequestParam(required = false) String folder,
                                        @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            Path dir = workspaceService.snapshotRoot(Path.of(workspace)).toAbsolutePath().normalize();
            List<DocxImportService.ImportedFile> assets = new ArrayList<>();
            var result = docxImportService.importDocxToFiles(
                    file.getOriginalFilename(), file.getInputStream(), dir, folder, assets);
            Path template = dir.resolve(result.templatePath()).normalize();
            if (!template.startsWith(dir)) throw new IllegalArgumentException("Percorso non valido");
            Files.createDirectories(template.getParent()); // la cartella di destinazione può non esistere (es. ALBA)
            Files.writeString(template, result.html());
            List<String> assetPaths = assets.stream().map(DocxImportService.ImportedFile::path).toList();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(new ImportResultResponse(result.templatePath(), assetPaths));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Import fallito: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Salva un PDF come riferimento consultabile (non convertito). */
    @PostMapping(value = "/workspace/import-reference", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<?> importReference(@RequestParam String workspace,
                                             @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            String name = file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
            Path snap = workspaceService.snapshotRoot(Path.of(workspace)).toAbsolutePath().normalize();
            Path target = snap.resolve("assets/reference").resolve(name).normalize();
            if (!target.startsWith(snap))
                throw new IllegalArgumentException("Percorso non valido");
            Files.createDirectories(target.getParent());
            file.transferTo(target);
            String url = "/workspace/asset?workspace=" + URLEncoder.encode(Path.of(workspace).toString(), StandardCharsets.UTF_8)
                    + "&file=" + URLEncoder.encode("assets/reference/" + name, StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(new ReferenceResponse("assets/reference/" + name, url));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Salvataggio riferimento fallito: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/")
    public String loader() {
        return "loader";
    }

    @PostMapping("/workspace/load")
    public String loadWorkspace(@RequestParam String path, HttpSession session, Model model) {
        Path workspaceDir = Path.of(path);

        if (workspaceService.isSnapshotWorkspace(workspaceDir)) {
            session.setAttribute("workspacePath", path);
            // PRG: redirect a una GET condivisibile — il refresh del browser non ripete il POST
            return "redirect:/workspace?ws=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
        }
        if (workspaceService.isLegacyWorkspace(workspaceDir)) {
            model.addAttribute("error", "Workspace nel formato precedente (file direttamente nella root). "
                    + "Esegui la migrazione: python3 tools/migrate-workspace.py " + path);
            return "loader";
        }
        model.addAttribute("error", "Directory non valida: nessun template .html in " + path
                + " (attesa struttura <workspace>/snapshot/…)");
        return "loader";
    }

    /**
     * Workbench. Il workspace arriva da query string (condivisibile/bookmarkabile)
     * con fallback alla sessione; viene ri-validato a ogni accesso.
     */
    @GetMapping("/workspace")
    public String workspace(@RequestParam(required = false) String ws, HttpSession session) {
        String path = ws != null ? ws : (String) session.getAttribute("workspacePath");
        Path dir = path != null ? Path.of(path) : null;
        if (path == null || !workspaceService.isSnapshotWorkspace(dir)) {
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
                case String s when s.endsWith(".pdf") -> "application/pdf";
                default -> "application/octet-stream";
            };
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).body(image);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Immagine non servibile: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    public record SaveRequest(String workspace, String file, String content, String expectedContent) {}

    /**
     * Salva il contenuto di un file del workspace (sovrascrive).
     * Se expectedContent è fornito e non corrisponde al contenuto su disco → 409
     * (salvataggio ottimistico: il file è stato modificato da qualcun altro).
     */
    @PostMapping(value = "/workspace/file", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> saveFile(@RequestBody SaveRequest req) {
        try {
            Path dir = Path.of(req.workspace());
            Path target = dir.resolve(req.file()).normalize();
            if (!target.startsWith(dir.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Percorso fuori dal workspace: " + req.file());
            }
            if (req.expectedContent() != null && Files.exists(target)
                    && !Files.readString(target).equals(req.expectedContent())) {
                return ResponseEntity.status(409)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Il file è stato modificato su disco da un altro utente. Ricaricalo prima di salvare.".getBytes(StandardCharsets.UTF_8));
            }
            workspaceService.saveFile(dir, req.file(), req.content());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Salvataggio fallito: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Creazione di un file o cartella del workspace (Explorer file ops). */
    public record CreateRequest(String workspace, String path, String type) {}

    /**
     * Crea un file o una cartella. Un nuovo .html nasce con lo scaffold minimo, un .json con {}.
     */
    @PostMapping(value = "/workspace/node", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> createNode(@RequestBody CreateRequest req) {
        try {
            if (!"file".equals(req.type()) && !"dir".equals(req.type())) {
                throw new IllegalArgumentException("Tipo non valido: " + req.type());
            }
            Path dir = Path.of(req.workspace());
            workspaceService.createNode(dir, req.path(), "dir".equals(req.type()));
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("path", req.path().replace('\\', '/')));
        } catch (FileAlreadyExistsException e) {
            return ResponseEntity.status(409).contentType(MediaType.TEXT_PLAIN)
                    .body(("Esiste già: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Creazione fallita: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Richiesta di scansione riferimenti prima di una rinomina (dry-run). */
    public record RenameScanRequest(String workspace, String from) {}

    /**
     * Trova i riferimenti letterali al percorso che si vuole rinominare (include Thymeleaf,
     * link css, …): alimenta il dialogo di proposta con le checkbox pre-spuntate.
     */
    @PostMapping(value = "/workspace/rename/scan", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> renameScan(@RequestBody RenameScanRequest req) {
        try {
            var hits = workspaceService.scanReferences(Path.of(req.workspace()), req.from());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("references", hits));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Scansione fallita: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Rinomina con proposta di aggiornamento dei riferimenti trovati. */
    public record RenameRequest(String workspace, String from, String to, Boolean updateReferences) {}

    @PostMapping(value = "/workspace/rename", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> rename(@RequestBody RenameRequest req) {
        try {
            Path dir = Path.of(req.workspace());
            var moved = workspaceService.renameNode(dir, req.from(), req.to());
            var updated = Boolean.TRUE.equals(req.updateReferences())
                    ? workspaceService.updateReferences(dir, req.from(), req.to())
                    : List.of();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("moved", moved, "updated", updated));
        } catch (FileAlreadyExistsException e) {
            return ResponseEntity.status(409).contentType(MediaType.TEXT_PLAIN)
                    .body(("Esiste già: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Rinomina fallita: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Richiesta di render per preview e stampa. dirtyFiles = { percorso relativo → contenuto live }. */
    public record RenderRequest(String workspace, String template, Map<String, String> dirtyFiles) {}

    public record ReleaseOp(String workspace, String template, String note, String author,
                            Map<String, String> dirtyFiles) {}
    public record ReleaseSync(String workspace) {}

    @PostMapping(value = "/release/sync", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> sync(@RequestBody ReleaseSync req) {
        try {
            String msg = releaseService.syncFromRemote(wsOf(req.workspace(), null));
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                    .body(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Sync fallita: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }
    public record ReleaseApprove(String workspace, String document, int version, String approver, String effectiveFrom) {}
    public record ReleaseReject(String workspace, String document, int version, String reason) {}
    public record ReleaseRollback(String workspace, String document, int version, String author) {}

    private Path wsOf(String workspace, HttpSession session) {
        String p = workspace != null ? workspace : (String) session.getAttribute("workspacePath");
        if (p == null) throw new IllegalArgumentException("Nessun workspace caricato");
        return Path.of(p);
    }

    @PostMapping(value = "/release/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> draft(@RequestBody ReleaseOp req) {
        try {
            Path dir = wsOf(req.workspace(), null);
            String commit = releaseService.saveDraft(dir, req.template(), req.dirtyFiles(), req.note(), req.author());
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(commit.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Bozza fallita: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @PostMapping(value = "/release/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> publish(@RequestBody ReleaseOp req) {
        try {
            Path dir = wsOf(req.workspace(), null);
            var result = releaseService.publish(dir, req.template(), req.dirtyFiles(), req.note(),
                    req.author() != null ? req.author() : "workbench");
            String json = "{\"version\":" + result.version() + ",\"branch\":\"" + result.branch() + "\""
                    + (result.prUrl() != null ? ",\"prUrl\":\"" + result.prUrl() + "\"" : "") + "}";
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Pubblicazione fallita: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @PostMapping(value = "/release/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> approve(@RequestBody ReleaseApprove req) {
        try {
            releaseService.approve(wsOf(req.workspace(), null), req.document(), req.version(),
                    req.approver() != null ? req.approver() : "workbench", req.effectiveFrom());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Approvazione fallita: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @PostMapping(value = "/release/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> reject(@RequestBody ReleaseReject req) {
        try {
            releaseService.reject(wsOf(req.workspace(), null), req.document(), req.version(), req.reason());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Scarto fallito: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @PostMapping(value = "/release/rollback", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> rollback(@RequestBody ReleaseRollback req) {
        try {
            releaseService.rollback(wsOf(req.workspace(), null), req.document(), req.version(),
                    req.author() != null ? req.author() : "workbench");
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Rollback fallito: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/release/list")
    @ResponseBody
    public ResponseEntity<?> list(@RequestParam String workspace, @RequestParam String document) throws IOException {
        JsonNode index = releaseService.list(Path.of(workspace), document);
        if (index == null) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN)
                    .body("Documento mai pubblicato".getBytes(StandardCharsets.UTF_8));
        }
        // versione attiva calcolata a tempo di lettura (dopo un merge PR il campo non è nello snapshot)
        ((com.fasterxml.jackson.databind.node.ObjectNode) index).put("active", releaseService.activeVersionOf(Path.of(workspace), document));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(index.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/release/impact")
    @ResponseBody
    public List<ReleaseService.ImpactEntry> impact(@RequestParam String workspace, @RequestParam String file) throws IOException {
        return releaseService.impact(Path.of(workspace), file);
    }

    // ===== Explorer release (sola lettura) =====

    /** Albero delle release: documento → versioni (con stato) → file (propri vs dipendenze congelate). */
    @GetMapping("/workspace/release-tree")
    @ResponseBody
    public List<ReleaseService.ReleaseDocumentNode> releaseTree(@RequestParam String workspace) throws IOException {
        return releaseService.releaseTree(Path.of(workspace));
    }

    /** Contenuto di un file dentro una release (sola lettura: nessuna scrittura, mai). */
    @GetMapping("/workspace/release-file")
    @ResponseBody
    public ResponseEntity<?> releaseFile(@RequestParam String workspace, @RequestParam String path) {
        try {
            String content = releaseService.readReleaseFile(Path.of(workspace), path);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                    .body(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Lettura release fallita: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Carica una nuova immagine nell'area snapshot (Explorer → 🖼️). Rifiuta duplicati. */
    private static final List<String> IMAGE_EXT =
            List.of("png", "jpg", "jpeg", "gif", "webp", "svg");

    @PostMapping(value = "/workspace/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<?> uploadImage(@RequestParam String workspace,
                                         @RequestParam(required = false) String folder,
                                         @RequestParam(defaultValue = "false") boolean replace,
                                         @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            String name = file.getOriginalFilename();
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Nome file mancante");
            name = name.replace('\\', '/');
            if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
            String lower = name.toLowerCase();
            int dot = lower.lastIndexOf('.');
            if (dot < 0 || !IMAGE_EXT.contains(lower.substring(dot + 1))) {
                throw new IllegalArgumentException("Estensione non supportata (png, jpg, jpeg, gif, webp, svg)");
            }
            Path snapshot = workspaceService.snapshotRoot(Path.of(workspace)).toAbsolutePath().normalize();
            String folderRel = (folder == null || folder.isBlank()) ? "assets"
                    : folder.replace('\\', '/').replaceAll("^/+|/+$", "");
            if (folderRel.isBlank() || folderRel.contains("..") || folderRel.split("/")[0].startsWith(".")) {
                throw new IllegalArgumentException("Cartella non valida: " + folder);
            }
            Path target = snapshot.resolve(folderRel).resolve(name).normalize();
            if (!target.startsWith(snapshot)) throw new IllegalArgumentException("Percorso fuori dal workspace");
            if (Files.exists(target) && !replace) {
                return ResponseEntity.status(409).contentType(MediaType.TEXT_PLAIN)
                        .body(("Esiste già: " + folderRel + "/" + name
                                + " — rinomina il file o conferma la sovrascrittura.").getBytes(StandardCharsets.UTF_8));
            }
            Files.createDirectories(target.getParent());
            try (var in = file.getInputStream()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("path", folderRel + "/" + name));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Caricamento immagine fallito: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Richiesta di cancellazione (solo area snapshot; bloccata se il file è referenziato). */
    public record DeleteRequest(String workspace, String path) {}

    /**
     * Cancella un file (con la coppia json/html, atomicamente) o una cartella vuota.
     * Se il file è referenziato da altri template → 409 con la lista dei referenzianti:
n     * l'utente deve prima sostituire il file o aggiornare/rimuovere i riferimenti.
     */
    @PostMapping(value = "/workspace/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> deleteNode(@RequestBody DeleteRequest req) {
        try {
            Path dir = Path.of(req.workspace());
            Path snapshot = workspaceService.snapshotRoot(dir).toAbsolutePath().normalize();
            boolean isDir = Files.isDirectory(snapshot.resolve(req.path().replace('\\', '/')).normalize());
            if (!isDir) {
                var refs = workspaceService.scanReferences(dir, req.path());
                if (!refs.isEmpty()) {
                    List<String> files = refs.stream().map(WorkspaceService.ReferenceHit::file).distinct().toList();
                    return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("referencedBy", files));
                }
            }
            var deleted = workspaceService.deleteNode(dir, req.path());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("deleted", deleted));
        } catch (FileNotFoundException e) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN)
                    .body(("Non trovato: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Cancellazione fallita: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Immagine dentro una release pubblicata (sola lettura, per il viewer). */
    @GetMapping("/workspace/release-asset")
    @ResponseBody
    public ResponseEntity<?> releaseAsset(@RequestParam String workspace, @RequestParam String path) {
        try {
            byte[] bytes = releaseService.readReleaseAsset(Path.of(workspace), path);
            String lower = path.toLowerCase();
            String mime = "image/jpeg";
            if (lower.endsWith(".png")) mime = "image/png";
            else if (lower.endsWith(".gif")) mime = "image/gif";
            else if (lower.endsWith(".webp")) mime = "image/webp";
            else if (lower.endsWith(".svg")) mime = "image/svg+xml";
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).body(bytes);
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(("Immagine release non servibile: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Richiesta di stampa di una versione pubblicata (dal suo snapshot, mai dai file di lavoro). */
    public record ReleasePrintRequest(String workspace, String document, int version, String template) {}

    @PostMapping(value = "/release/print", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> printRelease(@RequestBody ReleasePrintRequest req) {
        try {
            Path dir = Path.of(req.workspace());
            Path files = releaseService.activeFilesDir(dir, req.document(), req.version());
            if (files == null) throw new IllegalArgumentException("Versione non trovata: v" + req.version());
            String jsonRel = req.template().replaceAll("(?i)\\.html$", ".json");
            String jsonContent = Files.exists(files.resolve(jsonRel))
                    ? Files.readString(files.resolve(jsonRel)) : null;
            String html = workspaceService.renderTemplate(files, req.template(), jsonContent);
            html = workspaceService.inlineLinkedAssets(html, files, Map.of(), WorkspaceService.AssetTarget.PRINT);
            String base = req.template().replace(".html", "").replace("/", "-");
            String filename = base + "-v" + req.version() + ".pdf";
            byte[] pdf = pdfService.generatePdf(html);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(pdf.length)
                    .body(new ByteArrayResource(pdf));
        } catch (Exception e) {
            String msg = "Impossibile generare il PDF della release: " + e.getMessage();
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body(msg.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Anteprima di una versione pubblicata (renderizzata dal suo snapshot, con il JSON snapshotto). */
    @PostMapping(value = "/release/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String previewRelease(@RequestBody RenderRequest req, @RequestParam int version,
                                 HttpSession session, Model model) {
        String workspacePath = req.workspace() != null ? req.workspace() : (String) session.getAttribute("workspacePath");
        if (workspacePath == null) {
            model.addAttribute("error", "Nessun workspace caricato");
            return "fragments/preview";
        }
        try {
            Path dir = Path.of(workspacePath);
            String document = req.template().replaceAll("(?i)\\.html$", "");
            Path files = releaseService.activeFilesDir(dir, document, version);
            if (files == null) throw new IllegalArgumentException("Versione non trovata: v" + version);
            String jsonRel = req.template().replaceAll("(?i)\\.html$", ".json");
            String jsonContent = Files.exists(files.resolve(jsonRel))
                    ? Files.readString(files.resolve(jsonRel)) : null;
            String html = workspaceService.renderTemplate(files, req.template(), jsonContent);
            // data-URI: lo snapshot pubblicato è autonomo, le immagini non passano dall'endpoint asset
            html = workspaceService.inlineLinkedAssets(html, files, Map.of(), WorkspaceService.AssetTarget.PRINT);
            model.addAttribute("previewHtml", html);
        } catch (Exception e) {
            model.addAttribute("error", "Errore: " + e.getMessage());
        }
        return "fragments/preview";
    }

    /** Risolve un percorso relativo impedendo path-traversal fuori dal workspace. */
    private Path resolveInWorkspace(Path dir, String relPath) {
        Path root = workspaceService.snapshotRoot(dir).toAbsolutePath().normalize();
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
