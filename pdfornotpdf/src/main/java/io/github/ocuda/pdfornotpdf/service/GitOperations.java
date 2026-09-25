package io.github.ocuda.pdfornotpdf.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Plumbing git del workspace (E-Proposte/04 / R3): repo unico alla root, commit per percorsi,
 * branch candidate, allineamento col remote. Nessuna logica di ciclo di vita: le primitive
 * sono usate da ReleaseService, che resta il proprietario del dominio release.
 */
@Service
public class GitOperations {

    private final WorkspaceService workspaceService;
    private final RemoteConfig remote;

    public GitOperations(WorkspaceService workspaceService, RemoteConfig remote) {
        this.workspaceService = workspaceService;
        this.remote = remote;
    }

    private boolean remoteEnabled() {
        return remote.enabled();
    }

    CredentialsProvider credentials() {
        return new UsernamePasswordCredentialsProvider(remote.user(), remote.password());
    }

    /**
     * Garantisce il repo git unico alla root del workspace e condivide la storia col remote.
     * - repo assente + remote con main → adotta origin/main come base, poi committa lo stato locale
     * - repo assente senza remote → init + commit dello stato corrente
     * - repo presente → allineamento con origin/main (ff o merge; conflitti segnalati)
     */
    public void ensureRepo(Path ws, String author) throws IOException {
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
                CredentialsProvider cp = credentials();
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
     * Committa nel repo del workspace solo i percorsi indicati (es. "snapshot", "release"):
     * le bozze e le pubblicazioni hanno history intrecciata ma staging separato.
     * Repo creato al primo uso. Se non ci sono cambiamenti, non committa (ritorna null).
     */
    public String commitPaths(Path ws, List<String> pathPatterns, String message, String author) throws IOException {
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

    /**
     * Candidate su branch dedicato nel repo unico: viene committata SOLA la directory release/,
     * così la PR sul remote contiene esattamente la pubblicazione. Prima del push viene spinta
     * anche main (mirror completo): il branch candidate nasce da main, quindi resta merge-abile.
     */
    public void pushCandidateBranch(Path ws, String branch, String message, String author) throws IOException {
        CredentialsProvider cp = credentials();
        try (Git git = Git.open(ws.toFile())) {
            ensureOrigin(git);
            pushMainIfPossible(git, cp);
            git.checkout().setCreateBranch(true).setName(branch).call();
            git.add().addFilepattern(WorkspaceService.RELEASE_DIR + "/").call();
            git.add().addFilepattern(WorkspaceService.RELEASE_DIR + "/").setUpdate(true).call();
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

    /** Configurazione del remote "origin" (idempotente): lo aggiunge dalla config se manca. */
    public void ensureOrigin(Git git) throws IOException {
        try {
            boolean has = git.remoteList().call().stream().anyMatch(r -> "origin".equals(r.getName()));
            if (!has) {
                git.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(remote.url())).call();
            }
        } catch (Exception e) {
            throw new IOException("Configurazione remote fallita: " + e.getMessage(), e);
        }
    }

    /** Push best-effort di main: se il remote rifiuta (divergenza), lo gestisce la sincronizzazione. */
    public void pushMainIfPossible(Git git, CredentialsProvider cp) {
        try {
            var results = git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main"))
                    .setCredentialsProvider(cp).call();
            for (var result : results) {
                for (RemoteRefUpdate u : result.getRemoteUpdates()) {
                    org.slf4j.LoggerFactory.getLogger(GitOperations.class)
                            .debug("[release-sync] push main: {}", u.getStatus());
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GitOperations.class)
                    .warn("[release-sync] push main non riuscito (lo gestisce la sincronizzazione): {}", e.getMessage());
        }
    }

    /**
     * Allinea origin/main dentro main locale (merge ff; mai riscritture). In conflitto, elenca
     * i file con marker (audit 03 / B6): il working tree resta da risolvere a mano.
     */
    public void alignWithRemote(Git git, org.eclipse.jgit.lib.ObjectId originMain) throws Exception {
        try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            var local = walk.parseCommit(git.getRepository().resolve("refs/heads/main"));
            var remote = walk.parseCommit(originMain);
            if (walk.isMergedInto(remote, local)) return; // locale è avanti (o uguale)
        }
        var result = git.merge().include(originMain).call();
        var st = result.getMergeStatus();
        if (st != org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD
                && st != org.eclipse.jgit.api.MergeResult.MergeStatus.ALREADY_UP_TO_DATE
                && st != org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED) {
            // audit 03 / B6: i marker di conflitto sono già nel working tree — la UI riceve
            // l'elenco dei file da risolvere invece di un generico fallimento
            var conflitti = new java.util.ArrayList<String>(result.getConflicts().keySet());
            conflitti.sort(String::compareTo);
            throw new IOException("Sync in conflitto con origin/main (" + st + "): risolvi a mano i file "
                    + conflitti + " nel workspace (i marker <<<<<<< sono nei file), committa e riprova. "
                    + "Le bozze locali non sono state toccate.");
        }
    }
}
