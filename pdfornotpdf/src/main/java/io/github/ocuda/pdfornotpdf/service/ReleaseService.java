package io.github.ocuda.pdfornotpdf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ciclo di vita dei documenti: bozza → candidata → approvata (+ rollback).
 *
 * Struttura su disco (dentro il workspace, fuori dall'area snapshot ed esclusa dall'Explorer):
 *
 *   <workspace>/release/CLIENTE_A/fattura/
 *       ├── index.json                    # versioni, stati, versione attiva
 *       └── v7/
 *           ├── manifest.json             # file + sha256 + metadati creazione
 *           └── files/                    # SNAPSHOT completo della chiusura
 *               ├── CLIENTE_A/fattura.html
 *               ├── CLIENTE_A/include/…, STANDARD/include/…, assets/…
 *               └── CLIENTE_A/fattura.json
 *
 * Ogni directory di versione è una chiusura completa e autonoma: il render punta lì e gli
 * include/CSS/immagini risolvono dentro lo snapshot. Le versioni approvate sono immutabili.
 *
 * Git: UN SOLO repository alla root del workspace (mirror completo su remote, niente forge
 * richiesta — bare repo via SSH sufficiente):
 *   - <workspace>/.git            → commit bozze (snapshot/), pubblicazioni (release/),
 *                                   approvazioni e rollback; main mirror di origin/main.
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final WorkspaceService workspaceService;
    private final GitOperations gitOps;
    private final GiteaClient gitea;
    private final RemoteConfig remote;
    private final ObjectMapper om = new ObjectMapper();

    private volatile Path lastWorkspaceDir;

    private boolean remoteEnabled() {
        return remote.enabled();
    }

    /** Il scheduler e i pulsanti di sync operano sull'ultimo workspace caricato. */
    public void noteWorkspace(Path ws) {
        this.lastWorkspaceDir = ws;
    }

    private static final Pattern INCLUDE_REF = Pattern.compile("~\\{([^}]*)\\}");

    public ReleaseService(WorkspaceService workspaceService, GitOperations gitOps,
                          GiteaClient gitea, RemoteConfig remote) {
        this.workspaceService = workspaceService;
        this.gitOps = gitOps;
        this.gitea = gitea;
        this.remote = remote;
    }

    // ===== Record API =====

    public record VersionInfo(int version, String status, String createdBy, String createdAt,
                              String note, String approvedBy, String approvedAt, String effectiveFrom) {}

    public record ImpactEntry(String document, int version, String status) {}

    // ===== Percorsi =====

    private Path releasesRoot(Path ws) {
        return ws.resolve(WorkspaceService.RELEASE_DIR);
    }

    private Path docDir(Path ws, String document) {
        WorkspacePaths.validateRelPath(document); // B5: il document arriva da parametri di request
        return releasesRoot(ws).resolve(document);
    }

    private Path filesDirOf(Path ws, String document, int version) {
        return docDir(ws, document).resolve("v" + version).resolve("files");
    }

    /** "CLIENTE_A/fattura.html" → "CLIENTE_A/fattura" */
    public String documentOf(String templateRel) {
        return templateRel.replaceAll("(?i)\\.html$", "");
    }

    // ===== Chiusura delle dipendenze =====

    /**
     * Risolve ricorsivamente le dipendenze di un template: th:replace/insert/include
     * (frammenti, ricorsivi), <link rel="stylesheet"> e <img src>. Percorsi relativi
     * alla root del workspace.
     */
    public Set<String> resolveClosure(Path ws, String templateRel, Map<String, String> overlay) throws IOException {
        Set<String> closure = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(normalizeHtmlRef(templateRel));
        while (!pending.isEmpty()) {
            String rel = pending.poll();
            if (!closure.add(rel)) continue;
            String content = overlay.containsKey(rel) ? overlay.get(rel)
                    : WorkspacePaths.readTextOrNull(workspaceService.snapshotRoot(ws), rel);
            if (content == null) continue; // file referenziato ma assente: si pubblica senza

            // frammenti th:replace / th:insert / th:include: ~{percorso :: frammento}
            // guardia S1: un riferimento che esce dallo snapshot (../) viene scartato
            Matcher m = INCLUDE_REF.matcher(content);
            while (m.find()) {
                String ref = m.group(1);
                if (ref.contains("::")) ref = ref.substring(0, ref.indexOf("::"));
                ref = stripQuotes(ref.trim());
                String relFrag = ref.isBlank() || WorkspacePaths.isExternal(ref) ? null : normalizeHtmlRef(ref);
                if (relFrag != null && WorkspacePaths.staysInside(relFrag)) pending.add(relFrag);
            }

            Document doc = Jsoup.parse(content);
            for (Element link : doc.select("link[rel~=stylesheet]")) {
                String href = stripQuotes(link.attr("href").trim());
                if (href.isBlank() || WorkspacePaths.isExternal(href)) continue;
                String relCss = WorkspacePaths.normalizeRel(href);
                if (WorkspacePaths.staysInside(relCss)) closure.add(relCss);
            }
            for (Element img : doc.select("img[src]")) {
                String s = stripQuotes(img.attr("src").trim());
                if (s.isBlank() || WorkspacePaths.isExternal(s)) continue;
                String relImg = WorkspacePaths.normalizeRel(s);
                if (WorkspacePaths.staysInside(relImg)) closure.add(relImg);
            }
        }
        // JSON accoppiato (contratto dati del documento)
        for (String rel : List.copyOf(closure)) {
            if (rel.toLowerCase().endsWith(".html")) closure.add(rel.replaceAll("(?i)\\.html$", ".json"));
        }
        return closure;
    }

    private static String normalizeHtmlRef(String ref) {
        String r = stripQuotes(ref.replace('\\', '/').trim());
        while (r.startsWith("./")) r = r.substring(2);
        if (!r.toLowerCase().endsWith(".html")) r = r + ".html";
        return r;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    // ===== Bozza (commit nel repo del workspace) =====

    /**
     * "Salva bozza del documento": salva su disco i file della chiusura ancora non salvati,
     * poi committa nel repo bozze del workspace (creato al primo uso).
     */
    public String saveDraft(Path ws, String templateRel, Map<String, String> dirtyFiles, String note, String author) throws IOException {
        Map<String, String> ov = dirtyFiles != null ? dirtyFiles : Map.of();
        Set<String> closure = resolveClosure(ws, templateRel, ov);
        Path snapshot = workspaceService.snapshotRoot(ws);
        for (String rel : closure) {
            if (ov.containsKey(rel)) {
                Path target = snapshot.resolve(rel).normalize();
                if (!target.startsWith(snapshot.toAbsolutePath().normalize())) continue;
                Files.createDirectories(target.getParent());
                Files.writeString(target, ov.get(rel));
            }
        }
        String msg = "bozza " + templateRel + (note != null && !note.isBlank() ? " — " + note : "");
        return gitOps.commitPaths(ws, List.of(WorkspaceService.SNAPSHOT_DIR), msg, author);
    }

    // ===== Pubblicazione (candidate) =====

    /**
     * Pubblica una CANDIDATA: snapshot immutabile della chiusura (con i buffer dirty
     * salvati su disco) + commit nel repo releases. Approvata da un approvatore, diventa
     * la versione attiva servita dall'API.
     *
     * @return il numero di versione creato
     */
    public record PublishResult(int version, String branch, String prUrl) {}

    public PublishResult publish(Path ws, String templateRel, Map<String, String> dirtyFiles, String note, String author) throws IOException {
        String document = documentOf(templateRel);

        // B1: precondizioni PRIMA di ogni scrittura — una publish fallita non deve
        // lasciare candidate orfane su disco né in index.json
        if (remoteEnabled()) {
            String openPull = gitea.findOpenPullForDocument(document);
            if (openPull != null) {
                throw new IOException("Esiste già una candidata in attesa di review per " + document
                        + " (" + openPull + "). Attendere il merge o chiuderla prima di ripubblicare.");
            }
        }

        // repo unico alla root del workspace + main allineata al remote (se configurato)
        gitOps.ensureRepo(ws, author);
        if (remoteEnabled()) syncFromRemote(ws);

        Map<String, String> ov = dirtyFiles != null ? dirtyFiles : Map.of();
        Set<String> closure = resolveClosure(ws, templateRel, ov);
        Path snapshot = workspaceService.snapshotRoot(ws);

        Path docDir = docDir(ws, document);
        Path indexFile = docDir.resolve("index.json");
        String indexBefore = Files.exists(indexFile) ? Files.readString(indexFile) : null;
        Path vDir = null;
        try {

        // i file dirty della chiusura vengono salvati su disco (la pubblicazione li rende persistenti)
        for (String rel : closure) {
            if (ov.containsKey(rel)) {
                Path target = snapshot.resolve(rel).normalize();
                if (!target.startsWith(snapshot.toAbsolutePath().normalize())) continue;
                Files.createDirectories(target.getParent());
                Files.writeString(target, ov.get(rel));
            }
        }

        ObjectNode index = readIndex(docDir, document);
        int version = nextVersion(index);
        vDir = docDir.resolve("v" + version);
        Path vFiles = vDir.resolve("files");
        Files.createDirectories(vFiles);

        // copia della chiusura (testo da overlay/disco, immagini in binario) + hash
        ArrayNode filesArr = om.createArrayNode();
        for (String rel : closure) {
            Path src = snapshot.resolve(rel).normalize();
            if (!src.startsWith(snapshot.toAbsolutePath().normalize())) continue;
            Path target = vFiles.resolve(rel);
            Files.createDirectories(target.getParent());
            byte[] bytes;
            if (ov.containsKey(rel)) {
                bytes = ov.get(rel).getBytes(StandardCharsets.UTF_8);
                Files.write(target, bytes);
            } else if (Files.exists(src)) {
                bytes = Files.readAllBytes(src);
                Files.write(target, bytes);
            } else {
                continue;
            }
            ObjectNode f = filesArr.addObject();
            f.put("path", rel);
            f.put("sha256", WorkspacePaths.sha256Hex(bytes));
        }

        ObjectNode manifest = om.createObjectNode();
        manifest.put("document", document);
        manifest.put("version", version);
        manifest.put("createdBy", author);
        manifest.put("createdAt", LocalDateTime.now().toString());
        if (note != null) manifest.put("note", note);
        manifest.set("files", filesArr);
        Files.writeString(vDir.resolve("manifest.json"), om.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));

        // index: nuova candidate
        ObjectNode vi = ((ArrayNode) index.withArray("/versions")).addObject();
        vi.put("version", version);
        vi.put("status", ReleaseStatus.CANDIDATE.value());
        vi.put("createdBy", author);
        vi.put("createdAt", LocalDateTime.now().toString());
        if (note != null && !note.isBlank()) vi.put("note", note);
        writeIndex(docDir, index);

        String branch = "candidate/" + document + "/v" + version;
        String prUrl = null;
        if (remoteEnabled()) {
            // flusso approvato: candidate su BRANCH dedicato + push + Pull Request.
            // Il merge della PR sul main del remote promuove la versione a "pubblicata";
            // l'API di produzione la scopre con la sincronizzazione periodica.
            gitOps.pushCandidateBranch(ws, branch, "publish " + document + " v" + version, author);
            if (remote.prAuto()) {
                prUrl = gitea.createPullRequest(document, version, branch, note, author);
            }
        } else {
            // fallback locale (nessun remote): commit su main + approvazione in-app
            gitOps.commitPaths(ws, List.of(WorkspaceService.RELEASE_DIR), "publish " + document + " v" + version
                    + (note != null && !note.isBlank() ? " — " + note : ""), author);
        }
        return new PublishResult(version, branch, prUrl);
        } catch (Exception e) {
            // B1 (D2=A): rollback SOLO degli artefatti release — i dirty salvati restano
            rollbackReleaseArtifacts(docDir, vDir, indexBefore);
            if (e instanceof IOException io) throw io;
            throw new IOException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Sincronizza il repo unico con il remote (mirror completo): fetch, allineamento di main
     * con il remote (merge ff se possibile), normalizzazione degli stati (candidate merge →
     * published) e push. Dopo il merge di una PR, questa operazione porta la nuova release
     * nell'API. MAI reset: le bozze locali non vengono toccate.
     */
    public String syncFromRemote(Path ws) throws IOException {
        if (!remoteEnabled()) return "Remote non configurato: niente da sincronizzare";
        gitOps.ensureRepo(ws, "workbench");
        CredentialsProvider cp = gitOps.credentials();
        try (Git git = Git.open(ws.toFile())) {
            gitOps.ensureOrigin(git);
            git.fetch().setRemote("origin").setCredentialsProvider(cp).call();
            boolean hasMain = git.branchList().call().stream().anyMatch(b -> "refs/heads/main".equals(b.getName()));
            if (!hasMain) {
                var originMain = git.getRepository().resolve("origin/main");
                if (originMain == null) return "Remote senza main: niente da allineare";
                git.checkout().setName("main").setCreateBranch(true).setStartPoint("origin/main").call();
            } else {
                git.checkout().setName("main").call();
                // allinea il remote dentro main (mai riscritture)
                var originMain = git.getRepository().resolve("origin/main");
                if (originMain != null) gitOps.alignWithRemote(git, originMain);
            }

            // normalizza: le versioni arrivate su main via PR merge sono "pubblicate"
            boolean changed = normalizePublishedStatuses(releasesRoot(ws));
            if (changed) {
                git.add().addFilepattern(WorkspaceService.RELEASE_DIR + "/").call();
                git.add().addFilepattern(WorkspaceService.RELEASE_DIR + "/").setUpdate(true).call();
                git.commit().setMessage("sync: versioni merge marcate come pubblicate")
                        .setAuthor(new PersonIdent("workbench", "workbench@pdforNotPdf.local"))
                        .setCommitter(new PersonIdent("workbench", "workbench@pdforNotPdf.local")).call();
            }
            // mirror: main locale → remote
            git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main"))
                    .setCredentialsProvider(cp).call();
            return "Sincronizzato con " + remote.url();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Sync fallita: " + e.getMessage(), e);
        }
    }

    /** B1: annulla gli artefatti release di una publish fallita (v<n>/ e index.json). */
    private void rollbackReleaseArtifacts(Path docDir, Path vDir, String indexBefore) {
        try {
            if (vDir != null && Files.exists(vDir)) {
                try (Stream<Path> walk = Files.walk(vDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                        try { Files.delete(q); } catch (IOException ignored) { }
                    });
                }
            }
            Path indexFile = docDir.resolve("index.json");
            if (indexBefore != null) Files.writeString(indexFile, indexBefore);
            else Files.deleteIfExists(indexFile);
        } catch (IOException e) {
            log.error("Rollback publish non riuscito: {}", e.getMessage());
        }
    }

    private boolean normalizePublishedStatuses(Path root) throws IOException {
        if (!Files.isDirectory(root)) return false; // nessuna release ancora pubblicata
        boolean changed = false;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path idx : walk.filter(p -> "index.json".equals(p.getFileName().toString())
                    && !p.toString().contains(".git")).toList()) {
                JsonNode node = om.readTree(Files.readString(idx));
                boolean mod = false;
                for (JsonNode v : node.withArray("/versions")) {
                    if (ReleaseStatus.CANDIDATE.value().equals(v.path("status").asText())) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) v).put("status", ReleaseStatus.PUBLISHED.value());
                        mod = true;
                    }
                }
                if (mod) {
                    Files.writeString(idx, om.writerWithDefaultPrettyPrinter().writeValueAsString(node));
                    changed = true;
                }
            }
        } catch (UncheckedIOException e) {
            log.warn("Normalizzazione stati parziale (sotto-cartella illeggibile in {}): {}", root, e.getMessage());
        }
        return changed;
    }

    @Scheduled(fixedDelayString = "${app.release.sync-interval-ms:30000}", initialDelayString = "${app.release.sync-interval-ms:30000}")
    public void scheduledSync() {
        if (!remoteEnabled() || lastWorkspaceDir == null) return;
        try {
            syncFromRemote(lastWorkspaceDir);
        } catch (Exception e) {
            log.warn("[release-sync] sincronizzazione programmata non riuscita: {}", e.getMessage());
        }
    }

    /** Branch candidate/*: candidate in attesa di merge sul remote. */
    public List<Map<String, Object>> candidates(Path ws) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.exists(ws.resolve(".git"))) return result;
        try (Git git = Git.open(ws.toFile())) {
            for (Ref ref : git.branchList().call()) {
                String name = ref.getName().replace("refs/heads/", "");
                if (!name.startsWith("candidate/")) continue;
                String msg = "";
                for (RevCommit c : git.log().add(ref.getObjectId()).setMaxCount(1).call()) {
                    msg = c.getShortMessage();
                }
                result.add(Map.of("branch", name, "commit", msg));
            }
        } catch (Exception e) {
            throw new IOException("Lettura candidate fallita: " + e.getMessage(), e);
        }
        return result;
    }

    // ===== Approvazione / scarto / rollback =====

    /** Approva una candidate (o ri-approva una versione esistente), con eventuale data di efficacia. */
    public void approve(Path ws, String document, int version, String approver, String effectiveFrom) throws IOException {
        if (remoteEnabled()) {
            throw new IOException("Con il remote configurato l'approvazione avviene con il merge della Pull Request su Gitea.");
        }
        ObjectNode index = readIndex(docDir(ws, document), document);
        ObjectNode vi = findVersion(index, version);
        if (vi == null) throw new IOException("Versione inesistente: v" + version);
        if (ReleaseStatus.REJECTED.matches(vi.path("status").asText())) throw new IOException("Impossibile approvare una versione scartata");

        vi.put("status", ReleaseStatus.APPROVED.value());
        vi.put("approvedBy", approver);
        vi.put("approvedAt", LocalDateTime.now().toString());
        if (effectiveFrom != null && !effectiveFrom.isBlank()) vi.put("effectiveFrom", effectiveFrom);

        index.put("active", resolveActiveVersion(index));
        writeIndex(docDir(ws, document), index);
        gitOps.commitPaths(ws, List.of(WorkspaceService.RELEASE_DIR), "approve " + document + " v" + version
                + (effectiveFrom != null && !effectiveFrom.isBlank() ? " (efficacia " + effectiveFrom + ")" : ""), approver);
    }

    public void reject(Path ws, String document, int version, String reason) throws IOException {
        ObjectNode index = readIndex(docDir(ws, document), document);
        ObjectNode vi = findVersion(index, version);
        if (vi == null) throw new IOException("Versione inesistente: v" + version);
        if (ReleaseStatus.APPROVED.matches(vi.path("status").asText()) && index.path("active").asInt() == version) {
            throw new IOException("Impossibile scartare la versione attiva: esegui prima un rollback");
        }
        vi.put("status", ReleaseStatus.REJECTED.value());
        if (reason != null && !reason.isBlank()) vi.put("note", vi.path("note").asText("") + " — scartata: " + reason);
        writeIndex(docDir(ws, document), index);
        gitOps.commitPaths(ws, List.of(WorkspaceService.RELEASE_DIR), "reject " + document + " v" + version, "workbench");
    }

    /** Riattiva una versione approvata precedente. */
    public void rollback(Path ws, String document, int version, String author) throws IOException {
        ObjectNode index = readIndex(docDir(ws, document), document);
        ObjectNode vi = findVersion(index, version);
        if (vi == null) throw new IOException("Versione inesistente: v" + version);
        String st = vi.path("status").asText();
        if (!ReleaseStatus.APPROVED.matches(st) && !ReleaseStatus.PUBLISHED.matches(st))
            throw new IOException("Solo versioni approvate o pubblicate possono essere riattivate");
        // le versioni successive alla target vengono escluse dalla risoluzione (superseded)
        for (JsonNode v : index.withArray("/versions")) {
            if (v.path("version").asInt() > version
                    && (ReleaseStatus.APPROVED.matches(v.path("status").asText()) || ReleaseStatus.PUBLISHED.matches(v.path("status").asText()))) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) v).put("superseded", true);
            }
        }
        index.put("active", version);
        writeIndex(docDir(ws, document), index);
        gitOps.commitPaths(ws, List.of(WorkspaceService.RELEASE_DIR), "rollback " + document + " → v" + version, author);
        if (remoteEnabled()) {
            try (Git git = Git.open(ws.toFile())) {
                git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main"))
                        .setCredentialsProvider(gitOps.credentials()).call();
            } catch (Exception e) {
                throw new IOException("Push rollback fallito: " + e.getMessage(), e);
            }
        }
    }

    // ===== Lettura =====

    /** index.json del documento, oppure null se mai pubblicato. */
    public JsonNode list(Path ws, String document) throws IOException {
        Path f = docDir(ws, document).resolve("index.json");
        return Files.exists(f) ? om.readTree(Files.readString(f)) : null;
    }

    /** Versione attiva calcolata (published/approved con effectiveFrom più recente ≤ oggi). -1 se nessuna. */
    public int activeVersionOf(Path ws, String document) throws IOException {
        JsonNode idx = list(ws, document);
        if (idx == null) return -1;
        return resolveActiveVersion((ObjectNode) idx);
    }

    /** Directory files/ della versione attiva (o di una versione esplicita). null se non disponibile. */
    public Path activeFilesDir(Path ws, String document, Integer version) throws IOException {
        JsonNode indexNode = list(ws, document);
        if (indexNode == null) return null;
        ObjectNode index = (ObjectNode) indexNode;
        int v = (version != null) ? version : resolveActiveVersion(index);
        Path files = filesDirOf(ws, document, v);
        return Files.isDirectory(files) ? files : null;
    }

    /**
     * Impact analysis: i documenti la cui versione ATTIVA contiene il file indicato.
     * Serve a capire quali stampe pubblicate sono toccate da una modifica a un componente condiviso.
     */
    public List<ImpactEntry> impact(Path ws, String fileRel) throws IOException {
        List<ImpactEntry> result = new ArrayList<>();
        Path root = releasesRoot(ws);
        if (!Files.isDirectory(root)) return result;
        // i documenti sono annidati (releases/CLIENTE_A/preventivo/…): si cercano tutti gli index.json
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path idxFile : walk.filter(p -> p.getFileName().toString().equals("index.json")).toList()) {
                String document = root.relativize(idxFile.getParent()).toString().replace('\\', '/');
                JsonNode index = om.readTree(Files.readString(idxFile));
                int active = index.path("active").asInt(-1);
                if (active == -1) continue;
                Path manifest = idxFile.getParent().resolve("v" + active).resolve("manifest.json");
                if (!Files.exists(manifest)) continue;
                JsonNode mf = om.readTree(Files.readString(manifest));
                for (JsonNode f : mf.withArray("/files")) {
                    if (fileRel.equals(f.path("path").asText())) {
                        result.add(new ImpactEntry(document, active, ReleaseStatus.APPROVED.value()));
                        break;
                    }
                }
            }
        }
        return result;
    }

    // ===== Albero release (Explorer release, sola lettura) =====

    public record ReleaseFileEntry(String path, boolean dep) {}
    public record ReleaseVersionNode(int version, String status, boolean active, String note,
                                     List<ReleaseFileEntry> files) {}
    public record ReleaseDocumentNode(String document, int activeVersion, List<ReleaseVersionNode> versions) {}

    /**
     * Albero delle release per l'Explorer in sola lettura: documento → versioni (con stato) →
     * file della versione. I file fuori dalla cartella owner del documento sono marcati come
     * dipendenze congelate (dep=true) — l'UI li raggruppa in un nodo dedicato.
     */
    public List<ReleaseDocumentNode> releaseTree(Path ws) throws IOException {
        Path root = releasesRoot(ws);
        List<ReleaseDocumentNode> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path idx : walk.filter(p -> "index.json".equals(p.getFileName().toString())).toList()) {
                String document = root.relativize(idx.getParent()).toString().replace('\\', '/');
                JsonNode index = om.readTree(Files.readString(idx));
                int active = resolveActiveVersion((ObjectNode) index); // come /release/list: calcolato, non dal campo grezzo
                String owner = document.contains("/") ? document.substring(0, document.indexOf('/')) : document;
                List<ReleaseVersionNode> versions = new ArrayList<>();
                for (JsonNode v : index.withArray("/versions")) {
                    int ver = v.path("version").asInt();
                    Path vFiles = idx.getParent().resolve("v" + ver).resolve("files");
                    List<ReleaseFileEntry> files = new ArrayList<>();
                    if (Files.isDirectory(vFiles)) {
                        try (Stream<Path> w2 = Files.walk(vFiles)) {
                            for (Path f : w2.filter(Files::isRegularFile)
                                    .filter(p -> {
                                        String k = WorkspaceService.kindOf(p.getFileName().toString());
                                        return "html".equals(k) || "css".equals(k) || "json".equals(k) || "img".equals(k);
                                    })
                                    .sorted().toList()) {
                                String rel = vFiles.relativize(f).toString().replace('\\', '/');
                                files.add(new ReleaseFileEntry(document + "/v" + ver + "/files/" + rel,
                                        !rel.equals(owner) && !rel.startsWith(owner + "/")));
                            }
                        } catch (UncheckedIOException e) {
                            log.warn("File della versione v{} di {} non esplorabili: {}", ver, document, e.getMessage());
                        }
                    }
                    versions.add(new ReleaseVersionNode(ver, v.path("status").asText(), ver == active,
                            v.path("note").asText(null), files));
                }
                result.add(new ReleaseDocumentNode(document, active, versions));
            }
        } catch (UncheckedIOException e) {
            log.warn("Albero release parziale (sotto-cartella illeggibile in {}): {}", root, e.getMessage());
        }
        result.sort(java.util.Comparator.comparing(ReleaseDocumentNode::document));
        return result;
    }

    /**
     * Contenuto di un file dentro una release (sola lettura). Il percorso è relativo alla radice
     * release/ e deve trovarsi dentro un segmento vX/files/ (nessuna scrittura, mai).
     */
    public String readReleaseFile(Path ws, String relPath) throws IOException {
        String p = relPath.replace('\\', '/');
        if (p.startsWith("/") || !p.matches("^.*/v[0-9]+/files/.+$")) {
            throw new IllegalArgumentException("Percorso release non valido: " + relPath);
        }
        Path target = WorkspacePaths.resolveInside(releasesRoot(ws), p);
        if (!Files.isRegularFile(target)) throw new IOException("File non trovato: " + relPath);
        String k = WorkspaceService.kindOf(target.getFileName().toString());
        if (!("html".equals(k) || "css".equals(k) || "json".equals(k))) {
            throw new IOException("Tipo di file non visualizzabile: " + relPath);
        }
        return Files.readString(target);
    }

    /** Byte di un'immagine dentro una release (sola lettura), per il viewer dell'Explorer release. */
    public byte[] readReleaseAsset(Path ws, String relPath) throws IOException {
        String p = relPath.replace('\\', '/');
        if (p.startsWith("/") || !p.matches("^.*/v[0-9]+/files/.+$")) {
            throw new IllegalArgumentException("Percorso release non valido: " + relPath);
        }
        Path target = WorkspacePaths.resolveInside(releasesRoot(ws), p);
        if (!Files.isRegularFile(target)) throw new IOException("File non trovato: " + relPath);
        if (!"img".equals(WorkspaceService.kindOf(target.getFileName().toString()))) {
            throw new IOException("Non è un'immagine: " + relPath);
        }
        return Files.readAllBytes(target);
    }

    // ===== index.json =====

    private ObjectNode readIndex(Path docDir, String document) throws IOException {
        Path f = docDir.resolve("index.json");
        if (Files.exists(f)) return (ObjectNode) om.readTree(Files.readString(f));
        ObjectNode idx = om.createObjectNode();
        idx.put("document", document);
        idx.putNull("active");
        idx.set("versions", om.createArrayNode());
        return idx;
    }

    private void writeIndex(Path docDir, ObjectNode index) throws IOException {
        Files.createDirectories(docDir);
        Files.writeString(docDir.resolve("index.json"),
                om.writerWithDefaultPrettyPrinter().writeValueAsString(index));
    }

    private ObjectNode findVersion(ObjectNode index, int version) {
        for (JsonNode v : index.withArray("/versions")) {
            if (v.path("version").asInt(-1) == version) return (ObjectNode) v;
        }
        return null;
    }

    private int nextVersion(ObjectNode index) {
        int max = 0;
        for (JsonNode v : index.withArray("/versions")) {
            max = Math.max(max, v.path("version").asInt(0));
        }
        return max + 1;
    }

    /**
     * Versione attiva: tra le APPROVATE, quella con effectiveFrom più recente ≤ oggi
     * (a parità di data vince la versione più alta). Se nessuna è ancora in efficacia
     * si serve comunque l'ultima approvata (evitare un outage).
     */
    private int resolveActiveVersion(ObjectNode index) {
        LocalDate today = LocalDate.now();
        int best = -1;
        LocalDate bestEf = null;
        int maxApproved = -1;
        for (JsonNode v : index.withArray("/versions")) {
            // servibili: published (arrivate via PR merge) e approved (approvata in-app)
            String st = v.path("status").asText();
            if (!ReleaseStatus.APPROVED.matches(st) && !ReleaseStatus.PUBLISHED.matches(st)) continue;
            if (v.path("superseded").asBoolean(false)) continue; // escluse da un rollback
            int ver = v.path("version").asInt();
            maxApproved = Math.max(maxApproved, ver);
            String ef = v.path("effectiveFrom").asText(null);
            LocalDate efDate;
            try {
                efDate = ef != null ? LocalDate.parse(ef) : LocalDate.MIN;
            } catch (Exception e) {
                efDate = LocalDate.MIN;
            }
            if (efDate.isAfter(today)) continue; // non ancora in efficacia
            if (best == -1 || bestEf == null || efDate.isAfter(bestEf)
                    || (efDate.equals(bestEf) && ver > best)) {
                best = ver;
                bestEf = efDate;
            }
        }
        return best != -1 ? best : maxApproved;
    }

    // ===== Utility =====

}
