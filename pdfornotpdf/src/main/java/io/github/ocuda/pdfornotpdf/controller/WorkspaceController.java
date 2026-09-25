package io.github.ocuda.pdfornotpdf.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ocuda.pdfornotpdf.service.DocxImportService;
import io.github.ocuda.pdfornotpdf.service.PdfService;
import io.github.ocuda.pdfornotpdf.service.ReleaseService;
import io.github.ocuda.pdfornotpdf.service.WorkspaceService;
import io.github.ocuda.pdfornotpdf.web.ConflictException;
import io.github.ocuda.pdfornotpdf.web.GlobalExceptionHandler;
import io.github.ocuda.pdfornotpdf.web.ValidationException;
import io.github.ocuda.pdfornotpdf.service.WorkspacePaths;
import jakarta.servlet.http.HttpSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Workbench", description = "UI e API del workbench: explorer, editor, file-ops, release")
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

    /** Import DOCX → template HTML di partenza + immagini estratte in assets/import/.
     *  Re-import sullo stesso percorso → 409: la UI chiede conferma e riposta con overwrite=true (B3). */
    @Operation(summary = "Import DOCX: template HTML + immagini estratte (409 se esiste; overwrite=true)")
    @PostMapping(value = "/workspace/import-docx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<?> importDocx(@RequestParam String workspace,
                                        @RequestParam(required = false) String folder,
                                        @RequestParam(defaultValue = "false") boolean overwrite,
                                        @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws IOException {
        Path dir = workspaceService.snapshotRoot(Path.of(workspace)).toAbsolutePath().normalize();
        List<DocxImportService.ImportedFile> assets = new ArrayList<>();
        // B3: il check di esistenza + 409 vive nel service, PRIMA di qualsiasi scrittura
        var result = docxImportService.importDocxToFiles(
                file.getOriginalFilename(), file.getInputStream(), dir, folder, overwrite, assets);
        Path template = WorkspacePaths.resolveInside(dir, result.templatePath());
        Files.createDirectories(template.getParent()); // la cartella di destinazione può non esistere (es. ALBA)
        Files.writeString(template, result.html());
        List<String> assetPaths = assets.stream().map(DocxImportService.ImportedFile::path).toList();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(new ImportResultResponse(result.templatePath(), assetPaths));
    }

    /** Salva un PDF come riferimento consultabile (non convertito). */
    @Operation(summary = "Salva un PDF come riferimento consultabile (non convertito)")
    @PostMapping(value = "/workspace/import-reference", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<?> importReference(@RequestParam String workspace,
                                             @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws IOException {
        String name = file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
        WorkspacePaths.validateRelPath(name); // B6: ".." sopravviveva alla sanitizzazione charset
        Path snap = workspaceService.snapshotRoot(Path.of(workspace)).toAbsolutePath().normalize();
        Path target = snap.resolve("assets/reference").resolve(name).normalize();
        Files.createDirectories(target.getParent());
        file.transferTo(target);
        String url = "/workspace/asset?workspace=" + URLEncoder.encode(Path.of(workspace).toString(), StandardCharsets.UTF_8)
                + "&file=" + URLEncoder.encode("assets/reference/" + name, StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(new ReferenceResponse("assets/reference/" + name, url));
    }

    @Operation(summary = "Loader: pagina di selezione del workspace")
    @GetMapping("/")
    public String loader() {
        return "loader";
    }

    @Operation(summary = "Valida il workspace e reindirizza al workbench (PRG)")
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
    @Operation(summary = "Workbench: valida il workspace (query o sessione) e mostra la UI")
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
    @Operation(summary = "Albero ricorsivo dei file dell'area snapshot (Explorer)")
    @GetMapping("/workspace/tree")
    @ResponseBody
    public WorkspaceService.TreeNode tree(@RequestParam String workspace) throws IOException {
        return workspaceService.buildTree(Path.of(workspace));
    }

    /**
     * Serve raw file content from workspace. Used by JS to load files into editors.
     */
    @Operation(summary = "Contenuto raw di un file dello snapshot")
    @GetMapping("/workspace/file")
    @ResponseBody
    public String getFile(@RequestParam String workspace, @RequestParam String file) throws IOException {
        Path dir = Path.of(workspace);
        return WorkspacePaths.readTextInside(workspaceService.snapshotRoot(dir), file);
    }

    /**
     * Serve un'immagine del workspace per l'anteprima (src delle <img> riscritte dal server).
     */
    @Operation(summary = "Immagine o PDF del workspace per l'anteprima (CSP sandbox)")
    @GetMapping("/workspace/asset")
    @ResponseBody
    public ResponseEntity<?> getAsset(@RequestParam String workspace, @RequestParam String file) throws IOException {
        byte[] image = workspaceService.readImage(Path.of(workspace), file);
        // audit 03 / S3: sandbox CSP — un SVG servito inline non può eseguire script nell'origine dell'app
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(WorkspaceService.mimeFor(file))).body(image);
    }

    public record SaveRequest(String workspace, String file, String content, String expectedSha256) {}

    /**
     * Salva il contenuto di un file del workspace (sovrascrive).
     * Se expectedContent è fornito e non corrisponde al contenuto su disco → 409
     * (salvataggio ottimistico: il file è stato modificato da qualcun altro).
     */
    @Operation(summary = "Salvataggio con controllo ottimistico (409 su conflitto o file sparito)")
    @PostMapping(value = "/workspace/file", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> saveFile(@RequestBody SaveRequest req) throws IOException {
        Path dir = Path.of(req.workspace());
        // FIX (fase 4): i pre-check risolvevano contro la ROOT del workspace invece che contro
        // snapshot/ — l'optimistic-locking non poteva mai scattare (contratto già inviato dal frontend)
        Path root = workspaceService.snapshotRoot(dir).toAbsolutePath().normalize();
        Path target = root.resolve(req.file()).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Percorso fuori dal workspace: " + req.file());
        }
        // audit 03 / B4: se un ALTRO browser ha rinominato/cancellato il file, un salvataggio
        // lo "resusciterebbe" col vecchio nome → 409 con azione chiara
        if (!Files.exists(target)) {
            return ResponseEntity.status(409)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Il file non esiste più su disco (rinominato o cancellato da un altro utente): "
                            + "ricarica l'Explorer prima di salvare.".getBytes(StandardCharsets.UTF_8));
        }
        // P1 (audit 05): il confronto viaggia come hash (32 char) invece che come intero contenuto
        if (req.expectedSha256() != null && !req.expectedSha256().isBlank()) {
            String actual = WorkspacePaths.sha256Hex(Files.readAllBytes(target));
            if (!actual.equalsIgnoreCase(req.expectedSha256())) {
                return ResponseEntity.status(409)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Il file è stato modificato su disco da un altro utente. Ricaricalo prima di salvare.".getBytes(StandardCharsets.UTF_8));
            }
        }
        workspaceService.saveFile(dir, req.file(), req.content());
        return ResponseEntity.ok().build();
    }

    /** Creazione di un file o cartella del workspace (Explorer file ops). */
    public record CreateRequest(String workspace, String path, String type) {}

    /**
     * Crea un file o una cartella. Un nuovo .html nasce con lo scaffold minimo, un .json con {}.
     */
    @Operation(summary = "Creazione file/cartella (html con scaffold, json con {})")
    @PostMapping(value = "/workspace/node", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> createNode(@RequestBody CreateRequest req) throws IOException {
        if (!"file".equals(req.type()) && !"dir".equals(req.type())) {
            throw new IllegalArgumentException("Tipo non valido: " + req.type());
        }
        Path dir = Path.of(req.workspace());
        workspaceService.createNode(dir, req.path(), "dir".equals(req.type()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("path", req.path().replace('\\', '/')));
    }

    /** Richiesta di scansione riferimenti prima di una rinomina (dry-run). */
    public record RenameScanRequest(String workspace, String from) {}

    /**
     * Trova i riferimenti letterali al percorso che si vuole rinominare (include Thymeleaf,
     * link css, …): alimenta il dialogo di proposta con le checkbox pre-spuntate.
     */
    @Operation(summary = "Dry-run: riferimenti al percorso per il dialogo di rinomina")
    @PostMapping(value = "/workspace/rename/scan", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> renameScan(@RequestBody RenameScanRequest req) throws IOException {
        var hits = workspaceService.scanReferences(Path.of(req.workspace()), req.from());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("references", hits));
    }

    /** Rinomina con proposta di aggiornamento dei riferimenti trovati. */
    public record RenameRequest(String workspace, String from, String to, Boolean updateReferences) {}

    @Operation(summary = "Rinomina file/cartella (coppia html↔json) con aggiornamento riferimenti")
    @PostMapping(value = "/workspace/rename", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> rename(@RequestBody RenameRequest req) throws IOException {
        Path dir = Path.of(req.workspace());
        var moved = workspaceService.renameNode(dir, req.from(), req.to());
        var updated = Boolean.TRUE.equals(req.updateReferences())
                ? workspaceService.updateReferences(dir, req.from(), req.to())
                : List.of();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("moved", moved, "updated", updated));
    }

    /** Richiesta di render per preview e stampa. dirtyFiles = { percorso relativo → contenuto live }. */
    public record RenderRequest(String workspace, String template, Map<String, String> dirtyFiles) {}

    public record ReleaseOp(String workspace, String template, String note, String author,
                            Map<String, String> dirtyFiles) {}
    public record ReleaseSync(String workspace) {}

    @Operation(summary = "Sincronizza il repo unico col remote (mirror completo)")
    @PostMapping(value = "/release/sync", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> sync(@RequestBody ReleaseSync req) throws IOException {
        String msg = releaseService.syncFromRemote(wsOf(req.workspace()));
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .body(msg.getBytes(StandardCharsets.UTF_8));
    }
    public record ReleaseApprove(String workspace, String document, int version, String approver, String effectiveFrom) {}
    public record ReleaseReject(String workspace, String document, int version, String reason) {}
    public record ReleaseRollback(String workspace, String document, int version, String author) {}

    private Path wsOf(String workspace) {
        // B4 (audit 05): il parametro session era morto (sempre null) — con workspace assente
        // si otteneva un NPE → 500 invece del 400 pulito
        if (workspace == null || workspace.isBlank()) {
            throw new IllegalArgumentException("Nessun workspace caricato");
        }
        return Path.of(workspace);
    }

    @Operation(summary = "Bozza: dirty su disco + commit snapshot/")
    @PostMapping(value = "/release/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> draft(@RequestBody ReleaseOp req) throws IOException {
        Path dir = wsOf(req.workspace());
        String commit = releaseService.saveDraft(dir, req.template(), req.dirtyFiles(), req.note(), req.author());
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(commit.getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "Crea la candidate v<n> (con PR sul remote se configurato)")
    @PostMapping(value = "/release/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> publish(@RequestBody ReleaseOp req) throws IOException {
        Path dir = wsOf(req.workspace());
        var result = releaseService.publish(dir, req.template(), req.dirtyFiles(), req.note(),
                req.author() != null ? req.author() : "workbench");
        String json = "{\"version\":" + result.version() + ",\"branch\":\"" + result.branch() + "\""
                + (result.prUrl() != null ? ",\"prUrl\":\"" + result.prUrl() + "\"" : "") + "}";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "Approva una candidate (solo modalità locale)")
    @PostMapping(value = "/release/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> approve(@RequestBody ReleaseApprove req) throws IOException {
        releaseService.approve(wsOf(req.workspace()), req.document(), req.version(),
                req.approver() != null ? req.approver() : "workbench", req.effectiveFrom());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Scarta una candidate")
    @PostMapping(value = "/release/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> reject(@RequestBody ReleaseReject req) throws IOException {
        releaseService.reject(wsOf(req.workspace()), req.document(), req.version(), req.reason());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Riattiva una versione approvata precedente")
    @PostMapping(value = "/release/rollback", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> rollback(@RequestBody ReleaseRollback req) throws IOException {
        releaseService.rollback(wsOf(req.workspace()), req.document(), req.version(),
                req.author() != null ? req.author() : "workbench");
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Index versioni di un documento, con versione attiva calcolata")
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

    /** Candidate in attesa di merge sul remote (fase 4: endpoint mancante, il frontend lo chiamava invano). */
    @Operation(summary = "Candidate in attesa di merge sul remote")
    @GetMapping("/release/candidates")
    @ResponseBody
    public List<Map<String, Object>> candidates(@RequestParam String workspace) throws IOException {
        return releaseService.candidates(Path.of(workspace));
    }

    @Operation(summary = "Documenti attivi che includono un file condiviso")
    @GetMapping("/release/impact")
    @ResponseBody
    public List<ReleaseService.ImpactEntry> impact(@RequestParam String workspace, @RequestParam String file) throws IOException {
        return releaseService.impact(Path.of(workspace), file);
    }

    // ===== Explorer release (sola lettura) =====

    /** Albero delle release: documento → versioni (con stato) → file (propri vs dipendenze congelate). */
    @Operation(summary = "Albero delle release per l'Explorer (sola lettura)")
    @GetMapping("/workspace/release-tree")
    @ResponseBody
    public List<ReleaseService.ReleaseDocumentNode> releaseTree(@RequestParam String workspace) throws IOException {
        return releaseService.releaseTree(Path.of(workspace));
    }

    /** Contenuto di un file dentro una release (sola lettura: nessuna scrittura, mai). */
    @Operation(summary = "Contenuto di un file dentro una release (sola lettura)")
    @GetMapping("/workspace/release-file")
    @ResponseBody
    public ResponseEntity<?> releaseFile(@RequestParam String workspace, @RequestParam String path) throws IOException {
        String content = releaseService.readReleaseFile(Path.of(workspace), path);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Carica una nuova immagine nell'area snapshot (Explorer → 🖼️). Rifiuta duplicati. */
    private static final List<String> IMAGE_EXT =
            List.of("png", "jpg", "jpeg", "gif", "webp", "svg");

    @Operation(summary = "Carica un'immagine in snapshot/ (409 se esiste; replace=true sovrascrive)")
    @PostMapping(value = "/workspace/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<?> uploadImage(@RequestParam String workspace,
                                         @RequestParam(required = false) String folder,
                                         @RequestParam(defaultValue = "false") boolean replace,
                                         @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || !IMAGE_EXT.contains(lower.substring(dot + 1))) {
            throw new IllegalArgumentException("Estensione non supportata (png, jpg, jpeg, gif, webp, svg)");
        }
        try {
            // validazione percorsi e scrittura: tutta nel service (R6, chiude S5)
            String rel = workspaceService.saveUploadedImage(Path.of(workspace), folder, name,
                    file.getInputStream(), replace);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("path", rel));
        } catch (FileAlreadyExistsException e) {
            return ResponseEntity.status(409).contentType(MediaType.TEXT_PLAIN)
                    .body(("Esiste già: " + e.getMessage()
                            + " — rinomina il file o conferma la sovrascrittura.").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Richiesta di cancellazione (solo area snapshot; bloccata se il file è referenziato). */
    public record DeleteRequest(String workspace, String path) {}

    /**
     * Cancella un file (con la coppia json/html, atomicamente) o una cartella vuota.
     * Se il file è referenziato da altri template → 409 con la lista dei referenzianti:
     * l'utente deve prima sostituire il file o aggiornare/rimuovere i riferimenti.
     */
    @Operation(summary = "Cancellazione con blocco referenze (409 con l'elenco dei referenzianti)")
    @PostMapping(value = "/workspace/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> deleteNode(@RequestBody DeleteRequest req) throws IOException {
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
    }

    /** Immagine dentro una release pubblicata (sola lettura, per il viewer). */
    @Operation(summary = "Immagine dentro una release pubblicata (CSP sandbox)")
    @GetMapping("/workspace/release-asset")
    @ResponseBody
    public ResponseEntity<?> releaseAsset(@RequestParam String workspace, @RequestParam String path) throws IOException {
        byte[] bytes = releaseService.readReleaseAsset(Path.of(workspace), path);
        // audit 03 / S3: come /workspace/asset (stesso origine)
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(WorkspaceService.mimeFor(path))).body(bytes);
    }

    /** Richiesta di stampa di una versione pubblicata (dal suo snapshot, mai dai file di lavoro). */
    public record ReleasePrintRequest(String workspace, String document, int version, String template) {}

    @Operation(summary = "PDF di una versione pubblicata (snapshot congelato)")
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
            // contratto di stampa: 400 con messaggio presentabile; lo stacktrace va nei log (R7)
            throw new ValidationException("Impossibile generare il PDF della release: " + messageOf(e));
        }
    }

    /** Anteprima di una versione pubblicata (renderizzata dal suo snapshot, con il JSON snapshotto). */
    @Operation(summary = "Anteprima HTML di una versione pubblicata (snapshot congelato)")
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

    /**
     * Render template preview. Supports both file-based and editor-based content.
     */
    @Operation(summary = "HTML di anteprima dallo stato corrente (dirty-first) — fragment")
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

    @Operation(summary = "PDF dello stato corrente (dirty-first)")
    @PostMapping(value = "/workspace/print", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> printPdf(@RequestBody RenderRequest req, HttpSession session) {
        String workspacePath = req.workspace() != null ? req.workspace() : (String) session.getAttribute("workspacePath");
        if (workspacePath == null) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Nessun workspace caricato".getBytes(StandardCharsets.UTF_8));
        }

        try {
            Path dir = Path.of(workspacePath);
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
            // contratto di stampa: 400 con messaggio presentabile; lo stacktrace va nei log (R7)
            throw new ValidationException("Impossibile generare il PDF: " + messageOf(e));
        }
    }

    private static String messageOf(Exception e) {
        return GlobalExceptionHandler.messageOf(e);
    }
}
