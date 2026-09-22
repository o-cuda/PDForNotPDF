package io.github.ocuda.pdfornotpdf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

    private final WorkspaceService workspaceService;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${app.release.remote.url:}")
    private String remoteUrl;
    @Value("${app.release.remote.api:}")
    private String remoteApi;
    @Value("${app.release.remote.user:}")
    private String remoteUser;
    @Value("${app.release.remote.password:}")
    private String remotePassword;
    @Value("${app.release.remote.pr-auto:true}")
    private boolean prAuto;

    private volatile Path lastWorkspaceDir;

    private boolean remoteEnabled() {
        return remoteUrl != null && !remoteUrl.isBlank();
    }

    /** Il scheduler e i pulsanti di sync operano sull'ultimo workspace caricato. */
    public void noteWorkspace(Path ws) {
        this.lastWorkspaceDir = ws;
    }

    private static final Pattern INCLUDE_REF = Pattern.compile("~\\{([^}]*)\\}");
    private static final String RELEASE_DIR = "release";

    public ReleaseService(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    // ===== Record API =====

    public record VersionInfo(int version, String status, String createdBy, String createdAt,
                              String note, String approvedBy, String approvedAt, String effectiveFrom) {}

    public record ImpactEntry(String document, int version, String status) {}

    // ===== Percorsi =====

    private Path releasesRoot(Path ws) {
        return ws.resolve(RELEASE_DIR);
    }

    private Path docDir(Path ws, String document) {
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
                    : readQuiet(workspaceService.snapshotRoot(ws).resolve(rel));
            if (content == null) continue; // file referenziato ma assente: si pubblica senza

            // frammenti th:replace / th:insert / th:include: ~{percorso :: frammento}
            Matcher m = INCLUDE_REF.matcher(content);
            while (m.find()) {
                String ref = m.group(1);
                if (ref.contains("::")) ref = ref.substring(0, ref.indexOf("::"));
                ref = stripQuotes(ref.trim());
                if (!ref.isBlank() && !isExternal(ref)) pending.add(normalizeHtmlRef(ref));
            }

            Document doc = Jsoup.parse(content);
            for (Element link : doc.select("link[rel~=stylesheet]")) {
                String href = stripQuotes(link.attr("href").trim());
                if (!href.isBlank() && !isExternal(href)) closure.add(normalizeRel(href));
            }
            for (Element img : doc.select("img[src]")) {
                String s = stripQuotes(img.attr("src").trim());
                if (!s.isBlank() && !isExternal(s)) closure.add(normalizeRel(s));
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

    private static String normalizeRel(String href) {
        String r = stripQuotes(href.replace('\\', '/').trim());
        while (r.startsWith("/") || r.startsWith("./")) {
            r = r.startsWith("/") ? r.substring(1) : r.substring(2);
        }
        return r;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static boolean isExternal(String url) {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:") || url.startsWith("//");
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
        return commitPaths(ws, List.of(WorkspaceService.SNAPSHOT_DIR), msg, author);
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

        // repo unico alla root del workspace + main allineata al remote (se configurato)
        ensureRepo(ws, author);
        if (remoteEnabled()) syncFromRemote(ws);

        Map<String, String> ov = dirtyFiles != null ? dirtyFiles : Map.of();
        Set<String> closure = resolveClosure(ws, templateRel, ov);
        Path snapshot = workspaceService.snapshotRoot(ws);

        // i file dirty della chiusura vengono salvati su disco (la pubblicazione li rende persistenti)
        for (String rel : closure) {
            if (ov.containsKey(rel)) {
                Path target = snapshot.resolve(rel).normalize();
                if (!target.startsWith(snapshot.toAbsolutePath().normalize())) continue;
                Files.createDirectories(target.getParent());
                Files.writeString(target, ov.get(rel));
            }
        }

        Path docDir = docDir(ws, document);
        ObjectNode index = readIndex(docDir, document);
        int version = nextVersion(index);
        Path vDir = docDir.resolve("v" + version);
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
            f.put("sha256", sha256(bytes));
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
        vi.put("status", "candidate");
        vi.put("createdBy", author);
        vi.put("createdAt", LocalDateTime.now().toString());
        if (note != null && !note.isBlank()) vi.put("note", note);
        writeIndex(docDir, index);

        String branch = "candidate/" + document + "/v" + version;
        String prUrl = null;
        if (remoteEnabled()) {
            // una sola candidata aperta per documento: evita conflitti di merge su index.json
            String open = findOpenPullForDocument(document);
            if (open != null) {
                throw new IOException("Esiste già una candidata in attesa di review per " + document
                        + " (" + open + "). Attendere il merge o chiuderla prima di ripubblicare.");
            }
            // flusso approvato: candidate su BRANCH dedicato + push + Pull Request.
            // Il merge della PR sul main del remote promuove la versione a "pubblicata";
            // l'API di produzione la scopre con la sincronizzazione periodica.
            pushCandidateBranch(ws, branch, "publish " + document + " v" + version, author);
            if (prAuto) {
                prUrl = createPullRequest(document, version, branch, note, author);
            }
        } else {
            // fallback locale (nessun remote): commit su main + approvazione in-app
            commitPaths(ws, List.of(RELEASE_DIR), "publish " + document + " v" + version
                    + (note != null && !note.isBlank() ? " — " + note : ""), author);
        }
        return new PublishResult(version, branch, prUrl);
    }

    /**
     * Candidate su branch dedicato nel repo unico: viene committata SOLA la directory release/,
     * così la PR sul remote contiene esattamente la pubblicazione. Prima del push viene spinta
     * anche main (mirror completo): il branch candidate nasce da main, quindi resta merge-abile.
     */
    private void pushCandidateBranch(Path ws, String branch, String message, String author) throws IOException {
        CredentialsProvider cp = new UsernamePasswordCredentialsProvider(remoteUser, remotePassword);
        try (Git git = Git.open(ws.toFile())) {
            ensureOrigin(git);
            pushMainIfPossible(git, cp);
            git.checkout().setCreateBranch(true).setName(branch).call();
            git.add().addFilepattern(RELEASE_DIR + "/").call();
            git.add().addFilepattern(RELEASE_DIR + "/").setUpdate(true).call();
            PersonIdent ident = new PersonIdent(author, author.replaceAll("[^a-zA-Z0-9]", "") + "@pdforNotPdf.local");
            git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call();
            var results = git.push().setRemote("origin")
                    .setRefSpecs(new RefSpec(branch + ":" + branch))
                    .setCredentialsProvider(cp).call();
            for (var result : results) {
                for (RemoteRefUpdate u : result.getRemoteUpdates()) {
                    if (u.getStatus() != RemoteRefUpdate.Status.OK
                            && u.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw new IOException("Push fallito (" + u.getStatus() + "): "
                                + (u.getMessage() != null ? u.getMessage() : ""));
                    }
                }
            }
            git.checkout().setName("main").call(); // il working tree torna allo stato di main
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Push candidate fallito: " + e.getMessage(), e);
        }
    }

    /** Push best-effort di main: se il remote rifiuta (divergenza), lo gestisce la sincronizzazione. */
    private void pushMainIfPossible(Git git, CredentialsProvider cp) {
        try {
            var results = git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main"))
                    .setCredentialsProvider(cp).call();
            for (var result : results) {
                for (RemoteRefUpdate u : result.getRemoteUpdates()) {
                    System.err.println("[release-sync] push main: " + u.getStatus());
                }
            }
        } catch (Exception e) {
            System.err.println("[release-sync] push main non riuscito: " + e.getMessage());
        }
    }

    private void ensureOrigin(Git git) throws IOException {
        try {
            boolean has = git.remoteList().call().stream().anyMatch(r -> "origin".equals(r.getName()));
            if (!has) {
                git.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(remoteUrl)).call();
            }
        } catch (Exception e) {
            throw new IOException("Configurazione remote fallita: " + e.getMessage(), e);
        }
    }

    private String createPullRequest(String document, int version, String branch, String note, String author) throws IOException {
        String ownerRepo = remoteUrl.replaceFirst("\\.git$", "").replaceFirst("^https?://[^/]+/", "");
        String owner = ownerRepo.split("/")[0];
        String repo = ownerRepo.split("/")[1];
        String title = "Pubblica " + document + " v" + version;
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("title", title);
        payload.put("body", (note != null && !note.isBlank() ? note + "\n\n" : "") + "Candidate pubblicata da " + author);
        payload.put("head", branch);
        payload.put("base", "main");
        HttpRequest req = HttpRequest.newBuilder(URI.create(remoteApi + "/repos/" + owner + "/" + repo + "/pulls"))
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuth(remoteUser, remotePassword))
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload)))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return om.readTree(resp.body()).path("html_url").asText(null);
            }
            if (resp.statusCode() == 409) return null; // PR già esistente per il branch
            throw new IOException("Creazione PR fallita (" + resp.statusCode() + "): " + resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Creazione PR interrotta");
        }
    }

    /** Restituisce l'URL della PR aperta per il documento, se esiste. */
    private String findOpenPullForDocument(String document) throws IOException {
        String ownerRepo = remoteUrl.replaceFirst("\\.git$", "").replaceFirst("^https?://[^/]+/", "");
        String prefix = "candidate/" + document + "/";
        HttpRequest req = HttpRequest.newBuilder(URI.create(remoteApi + "/repos/"
                + ownerRepo.replace('/', '/') + "/pulls?state=open"))
                .header("Authorization", basicAuth(remoteUser, remotePassword))
                .GET().build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            for (JsonNode p : om.readTree(resp.body())) {
                String headRef = p.path("head").path("ref").asText();
                if (headRef.startsWith(prefix)) return p.path("html_url").asText(null);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sincronizza il repo unico con il remote (mirror completo): fetch, allineamento di main
     * con il remote (merge ff se possibile), normalizzazione degli stati (candidate merge →
     * published) e push. Dopo il merge di una PR, questa operazione porta la nuova release
     * nell'API. MAI reset: le bozze locali non vengono toccate.
     */
    public String syncFromRemote(Path ws) throws IOException {
        if (!remoteEnabled()) return "Remote non configurato: niente da sincronizzare";
        ensureRepo(ws, "workbench");
        CredentialsProvider cp = new UsernamePasswordCredentialsProvider(remoteUser, remotePassword);
        try (Git git = Git.open(ws.toFile())) {
            ensureOrigin(git);
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
                if (originMain != null) alignWithRemote(git, originMain);
            }

            // normalizza: le versioni arrivate su main via PR merge sono "pubblicate"
            boolean changed = normalizePublishedStatuses(releasesRoot(ws));
            if (changed) {
                git.add().addFilepattern(RELEASE_DIR + "/").call();
                git.add().addFilepattern(RELEASE_DIR + "/").setUpdate(true).call();
                git.commit().setMessage("sync: versioni merge marcate come pubblicate")
                        .setAuthor(new PersonIdent("workbench", "workbench@pdforNotPdf.local"))
                        .setCommitter(new PersonIdent("workbench", "workbench@pdforNotPdf.local")).call();
            }
            // mirror: main locale → remote
            git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main"))
                    .setCredentialsProvider(cp).call();
            return "Sincronizzato con " + remoteUrl;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Sync fallita: " + e.getMessage(), e);
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
                    if ("candidate".equals(v.path("status").asText())) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) v).put("status", "published");
                        mod = true;
                    }
                }
                if (mod) {
                    Files.writeString(idx, om.writerWithDefaultPrettyPrinter().writeValueAsString(node));
                    changed = true;
                }
            }
        }
        return changed;
    }

    @Scheduled(fixedDelayString = "${app.release.sync-interval-ms:30000}", initialDelayString = "${app.release.sync-interval-ms:30000}")
    public void scheduledSync() {
        if (!remoteEnabled() || lastWorkspaceDir == null) return;
        try {
            syncFromRemote(lastWorkspaceDir);
        } catch (Exception e) {
            System.err.println("[release-sync] " + e.getMessage());
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

    /**
     * Garantisce il repo git unico alla root del workspace e condivide la storia col remote.
     * - repo assente + remote con main → adotta origin/main come base, poi committa lo stato locale
     * - repo assente senza remote → init + commit dello stato corrente
     * - repo presente → allineamento con origin/main (ff o merge; conflitti segnalati)
     */
    private void ensureRepo(Path ws, String author) throws IOException {
        boolean existing = Files.exists(ws.resolve(".git"));
        Git git = null;
        try {
            git = existing ? Git.open(ws.toFile())
                    : Git.init().setDirectory(ws.toFile()).setInitialBranch("main").call();
            boolean hasSnapshot = Files.isDirectory(workspaceService.snapshotRoot(ws));
            String name = (author == null || author.isBlank()) ? "workbench" : author;
            PersonIdent ident = new PersonIdent(name, name.replaceAll("[^a-zA-Z0-9]", "") + "@pdforNotPdf.local");

            org.eclipse.jgit.lib.ObjectId originMain = null;
            if (remoteEnabled()) {
                ensureOrigin(git);
                CredentialsProvider cp = new UsernamePasswordCredentialsProvider(remoteUser, remotePassword);
                git.fetch().setRemote("origin").setCredentialsProvider(cp).call();
                originMain = git.getRepository().resolve("origin/main");
            }
            boolean hasMain = git.branchList().call().stream().anyMatch(b -> "refs/heads/main".equals(b.getName()));

            if (!hasMain && originMain != null) {
                // adotta la storia del remote come base, poi sovrappone lo stato locale
                git.checkout().setName("main").setCreateBranch(true).setStartPoint("origin/main").call();
                if (hasSnapshot) {
                    git.add().addFilepattern(".").call();
                    if (git.status().call().hasUncommittedChanges()) {
                        git.commit().setMessage("init workspace").setAuthor(ident).setCommitter(ident).call();
                    }
                }
            } else if (!hasMain) {
                if (hasSnapshot) {
                    git.add().addFilepattern(".").call();
                    git.commit().setMessage("init workspace").setAuthor(ident).setCommitter(ident).call();
                }
            } else {
                git.checkout().setName("main").call();
                if (originMain != null) alignWithRemote(git, originMain);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Init repo workspace fallito: " + e.getMessage(), e);
        } finally {
            if (git != null) git.close();
        }
    }

    /**
     * Allinea main locale con origin/main: nessuna azione se locale è avanti, fast-forward se
     * solo indietro, merge commit se divergenti (caso S1: bozze locali + PR mergiate). Mai reset.
     */
    private void alignWithRemote(Git git, org.eclipse.jgit.lib.ObjectId originMain) throws Exception {
        try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            var local = walk.parseCommit(git.getRepository().resolve("refs/heads/main"));
            var remote = walk.parseCommit(originMain);
            if (walk.isMergedInto(remote, local)) return; // locale è avanti (o uguale)
        }
        var result = git.merge().include(originMain).call();
        var st = result.getMergeStatus();
        if (st != MergeResult.MergeStatus.FAST_FORWARD
                && st != MergeResult.MergeStatus.ALREADY_UP_TO_DATE
                && st != MergeResult.MergeStatus.MERGED) {
            throw new IOException("Allineamento con origin/main non riuscito (stato: " + st
                    + "): risolvere a mano. Le bozze locali non sono state toccate.");
        }
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
        if ("rejected".equals(vi.path("status").asText())) throw new IOException("Impossibile approvare una versione scartata");

        vi.put("status", "approved");
        vi.put("approvedBy", approver);
        vi.put("approvedAt", LocalDateTime.now().toString());
        if (effectiveFrom != null && !effectiveFrom.isBlank()) vi.put("effectiveFrom", effectiveFrom);

        index.put("active", resolveActiveVersion(index));
        writeIndex(docDir(ws, document), index);
        commitPaths(ws, List.of(RELEASE_DIR), "approve " + document + " v" + version
                + (effectiveFrom != null && !effectiveFrom.isBlank() ? " (efficacia " + effectiveFrom + ")" : ""), approver);
    }

    public void reject(Path ws, String document, int version, String reason) throws IOException {
        ObjectNode index = readIndex(docDir(ws, document), document);
        ObjectNode vi = findVersion(index, version);
        if (vi == null) throw new IOException("Versione inesistente: v" + version);
        if ("approved".equals(vi.path("status").asText()) && index.path("active").asInt() == version) {
            throw new IOException("Impossibile scartare la versione attiva: esegui prima un rollback");
        }
        vi.put("status", "rejected");
        if (reason != null && !reason.isBlank()) vi.put("note", vi.path("note").asText("") + " — scartata: " + reason);
        writeIndex(docDir(ws, document), index);
        commitPaths(ws, List.of(RELEASE_DIR), "reject " + document + " v" + version, "workbench");
    }

    /** Riattiva una versione approvata precedente. */
    public void rollback(Path ws, String document, int version, String author) throws IOException {
        ObjectNode index = readIndex(docDir(ws, document), document);
        ObjectNode vi = findVersion(index, version);
        if (vi == null) throw new IOException("Versione inesistente: v" + version);
        String st = vi.path("status").asText();
        if (!"approved".equals(st) && !"published".equals(st))
            throw new IOException("Solo versioni approvate o pubblicate possono essere riattivate");
        // le versioni successive alla target vengono escluse dalla risoluzione (superseded)
        for (JsonNode v : index.withArray("/versions")) {
            if (v.path("version").asInt() > version
                    && ("approved".equals(v.path("status").asText()) || "published".equals(v.path("status").asText()))) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) v).put("superseded", true);
            }
        }
        index.put("active", version);
        writeIndex(docDir(ws, document), index);
        commitPaths(ws, List.of(RELEASE_DIR), "rollback " + document + " → v" + version, author);
        if (remoteEnabled()) {
            try (Git git = Git.open(ws.toFile())) {
                CredentialsProvider cp = new UsernamePasswordCredentialsProvider(remoteUser, remotePassword);
                git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main"))
                        .setCredentialsProvider(cp).call();
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
                        result.add(new ImpactEntry(document, active, "approved"));
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
                        }
                    }
                    versions.add(new ReleaseVersionNode(ver, v.path("status").asText(), ver == active,
                            v.path("note").asText(null), files));
                }
                result.add(new ReleaseDocumentNode(document, active, versions));
            }
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
        Path target = releasesRoot(ws).resolve(p).normalize();
        if (!target.startsWith(releasesRoot(ws).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Percorso fuori dal workspace: " + relPath);
        }
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
        Path target = releasesRoot(ws).resolve(p).normalize();
        if (!target.startsWith(releasesRoot(ws).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Percorso fuori dal workspace: " + relPath);
        }
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
            // servibili: "published" (arrivate via PR merge) e "approved" (approvata in-app)
            String st = v.path("status").asText();
            if (!"approved".equals(st) && !"published".equals(st)) continue;
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

    // ===== Git (repo unico alla root del workspace) =====

    /**
     * Committa nel repo del workspace solo i percorsi indicati (es. "snapshot", "release"):
     * le bozze e le pubblicazioni hanno history intrecciata ma staging separato.
     * Repo creato al primo uso. Se non ci sono cambiamenti, non committa (ritorna null).
     */
    private String commitPaths(Path ws, List<String> pathPatterns, String message, String author) throws IOException {
        boolean existing = Files.exists(ws.resolve(".git"));
        Git git = null;
        try {
            git = existing ? Git.open(ws.toFile())
                    : Git.init().setDirectory(ws.toFile()).setInitialBranch("main").call();
            String name = (author == null || author.isBlank()) ? "workbench" : author;
            PersonIdent ident = new PersonIdent(name, name.replaceAll("[^a-zA-Z0-9]", "") + "@pdforNotPdf.local");
            var before = git.status().call();
            if (!before.hasUncommittedChanges() && before.getUntracked().isEmpty()) return null;
            for (String p : pathPatterns) {
                String pattern = p.endsWith("/") ? p : p + "/";
                git.add().addFilepattern(pattern).call();
                git.add().addFilepattern(pattern).setUpdate(true).call();
            }
            // committa solo se i percorsi indicati hanno davvero modifiche in stage
            if (!git.status().call().hasUncommittedChanges()) return null;
            RevCommit rc = git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call();
            return rc.getId().getName();
        } catch (Exception e) {
            throw new IOException("Commit fallito: " + e.getMessage(), e);
        } finally {
            git.close();
        }
    }

    // ===== Utility =====

    private String readQuiet(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
