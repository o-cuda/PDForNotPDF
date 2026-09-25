package io.github.ocuda.pdfornotpdf.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test del ciclo di vita M1: chiusura delle dipendenze, snapshot (candidate),
 * approvazione con data di efficacia, rollback, impact analysis, repo git.
 */
class ReleaseServiceTest {

    private final RemoteConfig remote = new RemoteConfig("", "", "", "", true);
    private final ReleaseService service = new ReleaseService(new WorkspaceService(), new GitOperations(new WorkspaceService(), remote), new HttpGiteaClient(remote), remote);
    private final WorkspaceService workspaceService = new WorkspaceService();

    @TempDir
    Path ws;

    /** Percorso dentro l'area di lavoro (snapshot/). */
    private Path snap(String rel) { return ws.resolve("snapshot").resolve(rel); }

    @BeforeEach
    void setUp() throws IOException {
        // layout multi-tenant minimale con dipendenze a due livelli:
        // preventivo.html → header.html (CLIENTE_A) → logo.png (CLIENTE_A/assets)
        //                 → tabella-tariffe.html (STANDARD)
        //                 → common.css (STANDARD) + common.css (CLIENTE_A)
        Files.createDirectories(snap("STANDARD/include"));
        Files.createDirectories(snap("CLIENTE_A/include"));
        Files.createDirectories(snap("CLIENTE_A/assets"));

        Files.writeString(snap("STANDARD/tabella-tariffe.html"),
                "<table th:fragment=\"tariffe\"><tr><td>TARIFFE</td></tr></table>");
        Files.writeString(snap("STANDARD/include/common.css"), "body { color: #111; }\n");
        Files.writeString(snap("CLIENTE_A/include/common.css"), "h1 { color: #dc2626; }\n");
        Files.writeString(snap("CLIENTE_A/include/header.html"),
                "<header th:fragment=\"header\"><img src=\"CLIENTE_A/assets/logo.png\" />"
                + "<div th:replace=\"~{STANDARD/tabella-tariffe :: tariffe}\"></div></header>");
        Files.write(snap("CLIENTE_A/assets/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        Files.writeString(snap("CLIENTE_A/preventivo.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head>
                  <link rel="stylesheet" href="STANDARD/include/common.css" />
                  <link rel="stylesheet" href="CLIENTE_A/include/common.css" />
                </head>
                <body>
                  <div th:replace="~{CLIENTE_A/include/header :: header}"></div>
                  <h1 th:text="${titolo}">Titolo</h1>
                </body>
                </html>
                """);
        Files.writeString(snap("CLIENTE_A/preventivo.json"), "{\"titolo\":\"Bozza\"}");
    }

    // ===== Chiusura =====

    @Test
    void resolveClosureFindsTransitiveDependencies() throws IOException {
        Set<String> closure = service.resolveClosure(ws, "CLIENTE_A/preventivo.html", Map.of());

        assertTrue(closure.contains("CLIENTE_A/preventivo.html"));
        assertTrue(closure.contains("CLIENTE_A/include/common.css"));
        assertTrue(closure.contains("STANDARD/include/common.css"));
        assertTrue(closure.contains("CLIENTE_A/include/header.html"), "include di primo livello");
        assertTrue(closure.contains("STANDARD/tabella-tariffe.html"), "include transitive (dentro l'header)");
        assertTrue(closure.contains("CLIENTE_A/assets/logo.png"), "immagini referenziate");
        assertTrue(closure.contains("CLIENTE_A/preventivo.json"), "json accoppiato");
    }

    @Test
    void resolveClosurePrefersDirtyOverlay() throws IOException {
        Map<String, String> overlay = Map.of("CLIENTE_A/include/header.html",
                "<header th:fragment=\"header\"><img src=\"CLIENTE_A/assets/nuovo-logo.png\" /></header>");
        Set<String> closure = service.resolveClosure(ws, "CLIENTE_A/preventivo.html", overlay);
        assertTrue(closure.contains("CLIENTE_A/assets/nuovo-logo.png"),
                "l'overlay dirty può referenziare nuove dipendenze");
    }

    // ===== Pubblicazione =====

    @Test
    void publishCreatesCompleteSnapshotAndIndex() throws IOException {
        var result = service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), "prima pubblicazione", "mario");

        assertEquals(1, result.version());
        Path files = ws.resolve("release/CLIENTE_A/preventivo/v1/files");
        assertTrue(Files.exists(files.resolve("CLIENTE_A/preventivo.html")));
        assertTrue(Files.exists(files.resolve("CLIENTE_A/include/common.css")));
        assertTrue(Files.exists(files.resolve("STANDARD/include/common.css")));
        assertTrue(Files.exists(files.resolve("CLIENTE_A/include/header.html")));
        assertTrue(Files.exists(files.resolve("CLIENTE_A/assets/logo.png")), "le immagini sono nello snapshot");
        assertTrue(Files.exists(files.resolve("CLIENTE_A/preventivo.json")), "il json accoppiato è nello snapshot");
        assertTrue(Files.exists(ws.resolve("release/CLIENTE_A/preventivo/v1/manifest.json")));

        var index = service.list(ws, "CLIENTE_A/preventivo");
        assertEquals("candidate", index.path("versions").get(0).path("status").asText());
        assertEquals("prima pubblicazione", index.path("versions").get(0).path("note").asText());
    }

    @Test
    void publishSnapshotIsIsolatedFromLaterWorkspaceEdits() throws IOException {
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), null, "mario");
        // dopo la pubblicazione si modifica il template nel workspace
        Files.writeString(snap("CLIENTE_A/preventivo.html"), "<html><body>NUOVO</body></html>");

        String rendered = workspaceService.renderTemplate(
                ws.resolve("release/CLIENTE_A/preventivo/v1/files"), "CLIENTE_A/preventivo.html", "{}");
        assertFalse(rendered.contains("NUOVO"), "lo snapshot pubblicato non cambia con il workspace");
    }

    @Test
    void publishIncrementsVersionsAndSavesDirtyBuffers() throws IOException {
        var r1 = service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), null, "mario");
        var r2 = service.publish(ws, "CLIENTE_A/preventivo.html",
                Map.of("CLIENTE_A/include/common.css", "h1 { color: NUOVO; }"), null, "mario");

        assertEquals(1, r1.version());
        assertEquals(2, r2.version());
        String v2css = Files.readString(
                ws.resolve("release/CLIENTE_A/preventivo/v2/files/CLIENTE_A/include/common.css"));
        assertTrue(v2css.contains("NUOVO"), "il buffer dirty viene salvato e snapshotto");
    }

    // ===== Approvazione / efficacia / rollback =====

    private void seedTwoApproved() throws IOException {
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), "v1", "mario");
        service.approve(ws, "CLIENTE_A/preventivo", 1, "luca", null);
    }

    @Test
    void approveSetsActiveVersion() throws IOException {
        seedTwoApproved();
        var index = service.list(ws, "CLIENTE_A/preventivo");
        assertEquals("approved", index.path("versions").get(0).path("status").asText());
        assertEquals(1, index.path("active").asInt());
        assertEquals("luca", index.path("versions").get(0).path("approvedBy").asText());
    }

    @Test
    void approveWithFutureEffectiveFromKeepsPreviousVersionActive() throws IOException {
        seedTwoApproved(); // v1 approvata, in efficacia immediata
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), "v2", "mario");
        service.approve(ws, "CLIENTE_A/preventivo", 2, "luca",
                LocalDate.now().plusYears(1).toString()); // spartiacque di legge futuro

        var index = service.list(ws, "CLIENTE_A/preventivo");
        assertEquals(1, index.path("active").asInt(), "finché l'efficacia è futura resta attiva la v1");

        // a oggi la v2 non è in efficacia: la risoluzione dell'API deve restituire i file della v1
        Path activeFiles = service.activeFilesDir(ws, "CLIENTE_A/preventivo", null);
        assertTrue(activeFiles.toString().endsWith("v1/files"));
    }

    @Test
    void approveWithPastEffectiveFromActivatesImmediately() throws IOException {
        seedTwoApproved();
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), "v2", "mario");
        service.approve(ws, "CLIENTE_A/preventivo", 2, "luca",
                LocalDate.now().minusDays(1).toString());

        assertEquals(2, service.list(ws, "CLIENTE_A/preventivo").path("active").asInt());
    }

    @Test
    void rollbackReactivatesOlderApprovedVersion() throws IOException {
        seedTwoApproved();
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), "v2", "mario");
        service.approve(ws, "CLIENTE_A/preventivo", 2, "luca", null);
        assertEquals(2, service.list(ws, "CLIENTE_A/preventivo").path("active").asInt());

        service.rollback(ws, "CLIENTE_A/preventivo", 1, "luca");
        assertEquals(1, service.list(ws, "CLIENTE_A/preventivo").path("active").asInt());
    }

    @Test
    void rejectCandidateThenApproveFails() throws IOException {
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), null, "mario");
        service.reject(ws, "CLIENTE_A/preventivo", 1, "non ci sta");
        assertEquals("rejected", service.list(ws, "CLIENTE_A/preventivo").path("versions").get(0).path("status").asText());
        assertThrows(IOException.class, () -> service.approve(ws, "CLIENTE_A/preventivo", 1, "luca", null));
    }

    @Test
    void renderFromSnapshotUsesSnapshotJson() throws IOException {
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), null, "mario");
        // dopo la pubblicazione si cambia il json nel workspace
        Files.writeString(snap("CLIENTE_A/preventivo.json"), "{\"titolo\":\"CAMBIATO\"}");

        String rendered = workspaceService.renderTemplate(
                ws.resolve("release/CLIENTE_A/preventivo/v1/files"), "CLIENTE_A/preventivo.html",
                Files.readString(ws.resolve("release/CLIENTE_A/preventivo/v1/files/CLIENTE_A/preventivo.json")));
        assertTrue(rendered.contains("Bozza"), "lo snapshot json è quello pubblicato, non quello corrente");
        assertFalse(rendered.contains("CAMBIATO"));
    }

    // ===== Impact analysis =====

    @Test
    void impactFindsDocumentsBindingSharedFile() throws IOException {
        seedTwoApproved(); // v1 approvata: la chiusura contiene CLIENTE_A/include/common.css

        var impact = service.impact(ws, "CLIENTE_A/include/common.css");
        assertEquals(1, impact.size());
        assertEquals("CLIENTE_A/preventivo", impact.get(0).document());
        assertEquals(1, impact.get(0).version());

        assertTrue(service.impact(ws, "STANDARD/inesistente.css").isEmpty(),
                "file non referenziato → nessun impatto");
    }

    // ===== Explorer release (sola lettura) =====

    @Test
    void releaseTreeListsVersionsAndFlagsFrozenDependencies() throws IOException {
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), "prima", "mario");
        service.approve(ws, "CLIENTE_A/preventivo", 1, "luca", null);

        var tree = service.releaseTree(ws);
        assertEquals(1, tree.size());
        var doc = tree.get(0);
        assertEquals("CLIENTE_A/preventivo", doc.document());
        assertEquals(1, doc.activeVersion());
        assertEquals(1, doc.versions().size());
        var ver = doc.versions().get(0);
        assertEquals(1, ver.version());
        assertTrue(ver.active(), "v1 è la versione attiva");

        var ownExpected = "CLIENTE_A/preventivo/v1/files/CLIENTE_A/preventivo.html";
        var depExpected = "CLIENTE_A/preventivo/v1/files/STANDARD/include/common.css";
        var own = ver.files().stream().filter(f -> f.path().equals(ownExpected)).findFirst().orElseThrow();
        var dep = ver.files().stream().filter(f -> f.path().equals(depExpected)).findFirst().orElseThrow();
        assertFalse(own.dep(), "il file del cliente non è dipendenza");
        assertTrue(dep.dep(), "il file STANDARD è una dipendenza congelata");
    }

    @Test
    void readReleaseFileReturnsSnapshotContentAndRejectsInvalidPaths() throws IOException {
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), null, "mario");
        String rel = "CLIENTE_A/preventivo/v1/files/CLIENTE_A/preventivo.html";
        String content = service.readReleaseFile(ws, rel);
        assertTrue(content.contains("<html") && content.contains("CLIENTE_A/include/header"),
                "contenuto dello snapshot leggibile");

        for (String bad : new String[]{
                "CLIENTE_A/preventivo/index.json",        // fuori da vX/files/
                "CLIENTE_A/preventivo/v1/files/../../../../ecc.html",
                "CLIENTE_A/preventivo/v1/manifest.json"}) {
            try {
                service.readReleaseFile(ws, bad);
                org.junit.jupiter.api.Assertions.fail("percorso non valido accettato: " + bad);
            } catch (IllegalArgumentException | IOException expected) {
                // atteso
            }
        }
    }

    // ===== Immagini nelle release =====

    @Test
    void releaseTreeIncludesImagesAndReadReleaseAssetServesBytes() throws IOException {
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), null, "mario");

        var doc = service.releaseTree(ws).get(0);
        var ver = doc.versions().get(0);
        var imgPath = "CLIENTE_A/preventivo/v1/files/CLIENTE_A/assets/logo.png";
        var img = ver.files().stream().filter(f -> f.path().equals(imgPath)).findFirst().orElseThrow();
        assertFalse(img.dep(), "l'immagine del cliente non è dipendenza");

        byte[] bytes = service.readReleaseAsset(ws, imgPath);
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals('P', bytes[1]);

        // un css non è un'immagine → rifiutato
        try {
            service.readReleaseAsset(ws, "CLIENTE_A/preventivo/v1/files/CLIENTE_A/include/common.css");
            org.junit.jupiter.api.Assertions.fail("css accettato come immagine");
        } catch (IOException expected) { }
    }

    // ===== Git =====

    @Test
    void draftsCommitCreatesRepositoryInsideWorkspace() throws IOException {
        String commit = service.saveDraft(ws, "CLIENTE_A/preventivo.html", Map.of(), "prima bozza", "mario");
        assertNotNull(commit);
        assertTrue(Files.exists(ws.resolve(".git")), "repo bozze creato nel workspace");
    }

    @Test
    void singleRepositoryTracksSnapshotAndRelease() throws IOException {
        service.saveDraft(ws, "CLIENTE_A/preventivo.html", Map.of(), "bozza", "mario");
        service.publish(ws, "CLIENTE_A/preventivo.html", Map.of(), null, "mario");
        assertTrue(Files.exists(ws.resolve(".git")), "repo unico alla root del workspace");
        assertFalse(Files.exists(ws.resolve("release/.git")), "nessun repo annidato nella release");
        try (var git = org.eclipse.jgit.api.Git.open(ws.toFile())) {
            var head = git.getRepository().resolve("HEAD");
            try (var reader = git.getRepository().newObjectReader();
                 var rw = new org.eclipse.jgit.revwalk.RevWalk(reader);
                 var tw = new org.eclipse.jgit.treewalk.TreeWalk(reader)) {
                tw.addTree(rw.parseCommit(head).getTree());
                tw.setRecursive(true);
                boolean hasSnapshot = false, hasRelease = false;
                while (tw.next()) {
                    String path = tw.getPathString();
                    if (path.startsWith("snapshot/")) hasSnapshot = true;
                    if (path.startsWith("release/")) hasRelease = true;
                }
                assertTrue(hasSnapshot, "snapshot/ tracciata nel repo unico");
                assertTrue(hasRelease, "release/ tracciata nel repo unico");
            }
        }
    }

    // ===== Guardia S1: la chiusura ignora i riferimenti fuori dallo snapshot (T10) =====

    @Test
    void resolveClosureIgnoresReferencesOutsideSnapshot() throws IOException {
        Files.writeString(snap("CLIENTE_A/leak.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head><link rel="stylesheet" href="../segreto.css" /></head>
                <body><img src="../segreto.png" />
                <div th:replace="~{../fuori :: frag}"></div></body>
                </html>
                """);
        Set<String> closure = service.resolveClosure(ws, "CLIENTE_A/leak.html", Map.of());
        assertTrue(closure.contains("CLIENTE_A/leak.json"), "il JSON accoppiato resta nella chiusura");
        for (String rel : closure) {
            assertFalse(rel.contains(".."), "nessun riferimento fuori dallo snapshot nella chiusura: " + rel);
        }
    }

    // ===== WP2/WP4: publish atomica + flusso PR via FakeGiteaClient (T13, T18) =====

    /** Bare repo locale come remote: fetch/push git reali, forge simulata dal fake. */
    private Path bareRemote() throws Exception {
        Path bare = Files.createTempDirectory("bare-remote");
        org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bare.toFile()).call();
        return bare;
    }

    private ReleaseService serviceWithRemote(Path bare, FakeGiteaClient fake) {
        RemoteConfig cfg = new RemoteConfig(bare.toString(), "http://fake/api", "utente", "segreto", true);
        return new ReleaseService(new WorkspaceService(), new GitOperations(new WorkspaceService(), cfg), fake, cfg);
    }

    @Test
    void publishWithOpenPullLeavesNoTrace() throws Exception {
        // T13 (audit 05 / B1): la precondizione PR-aperta scatta PRIMA di ogni scrittura
        Path bare = bareRemote();
        FakeGiteaClient fake = new FakeGiteaClient();
        fake.openPullUrl = "http://fake/pr/9";
        ReleaseService remoteService = serviceWithRemote(bare, fake);

        IOException ex = assertThrows(IOException.class, () -> remoteService.publish(ws,
                "CLIENTE_A/preventivo.html", Map.of("CLIENTE_A/preventivo.html", "<html>dirty</html>"),
                "nota", "tester"));
        assertTrue(ex.getMessage().contains("Esiste già una candidata"));
        assertFalse(Files.exists(ws.resolve("release/CLIENTE_A/preventivo/v1")),
                "nessuna candidate orfana su disco (B1)");
        assertFalse(Files.exists(ws.resolve("release/CLIENTE_A/preventivo/index.json")),
                "index.json non toccato");
        org.assertj.core.api.Assertions.assertThat(bare).isNotEmptyDirectory(); // il remote esiste
        Files.walk(bare).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    @Test
    void publishCreateFailureRollsBackReleaseArtifactsButKeepsDirty() throws Exception {
        // T13-bis: push OK, creazione PR KO → rollback artefatti release; i dirty restano (D2=A)
        Path bare = bareRemote();
        FakeGiteaClient fake = new FakeGiteaClient();
        ReleaseService remoteService = serviceWithRemote(bare, fake);

        fake.failOnCreateMessage = "Gitea non raggiungibile";
        assertThrows(IOException.class, () -> remoteService.publish(ws,
                "CLIENTE_A/preventivo.html", Map.of("CLIENTE_A/preventivo.html", "<html>dirty</html>"),
                "v1", "tester"));

        // B1: la publish fallita non lascia artefatti sul disco
        // (nota: con remote, dopo publish+checkout main la release/ non è sul working tree
        //  per design — i file pubblicati tornano con la sync dopo il merge della PR)
        assertFalse(Files.exists(ws.resolve("release/CLIENTE_A/preventivo/v1")),
                "v1 rimossa dal rollback");
        assertFalse(Files.exists(ws.resolve("release/CLIENTE_A/preventivo/index.json")),
                "index.json scritto dalla publish fallita rimosso dal rollback");
        assertEquals("<html>dirty</html>", Files.readString(snap("CLIENTE_A/preventivo.html")),
                "D2=A: i dirty salvati restano su disco");
        Files.walk(bare).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    @Test
    void publishCreatesPullRequestViaFakeClient() throws Exception {
        // T18 (audit 05): il flusso candidate→PR è unit-testabile grazie all'interfaccia (R3)
        Path bare = bareRemote();
        FakeGiteaClient fake = new FakeGiteaClient();
        ReleaseService remoteService = serviceWithRemote(bare, fake);

        ReleaseService.PublishResult result = remoteService.publish(ws,
                "CLIENTE_A/preventivo.html", Map.of(), "nota di test", "tester");

        assertEquals(1, result.version());
        assertEquals(1, fake.createCalls);
        assertEquals("CLIENTE_A/preventivo", fake.lastDocument);
        assertEquals("candidate/CLIENTE_A/preventivo/v1", fake.lastBranch);
        assertEquals("http://fake/pr/1", result.prUrl());
        try (var git = org.eclipse.jgit.api.Git.open(bare.toFile())) {
            assertTrue(git.branchList().call().stream()
                            .anyMatch(r -> r.getName().endsWith("candidate/CLIENTE_A/preventivo/v1")),
                    "il branch candidate è stato spinto sul remote");
        }
        Files.walk(bare).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
}
