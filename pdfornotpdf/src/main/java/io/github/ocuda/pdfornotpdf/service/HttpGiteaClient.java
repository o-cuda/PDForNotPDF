package io.github.ocuda.pdfornotpdf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Implementazione HTTP del client Gitea (E-Proposte/04 / R3): le uniche due chiamate
 * del flusso release (creazione PR, ricerca PR aperta). Tutto il resto del ciclo di
 * vita è in ReleaseService; il git plumbing è in GitOperations.
 */
@Service
public class HttpGiteaClient implements GiteaClient {

    private final RemoteConfig remote;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public HttpGiteaClient(RemoteConfig remote) {
        this.remote = remote;
    }

    @Override
    public boolean isEnabled() {
        return remote.api() != null && !remote.api().isBlank();
    }

    @Override
    public String createPullRequest(String document, int version, String branch, String note, String author) throws IOException {
        String ownerRepo = ownerRepo();
        String owner = ownerRepo.split("/")[0];
        String repo = ownerRepo.split("/")[1];
        String title = "Pubblica " + document + " v" + version;
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("title", title);
        payload.put("body", (note != null && !note.isBlank() ? note + "\n\n" : "") + "Candidate pubblicata da " + author);
        payload.put("head", branch);
        payload.put("base", "main");
        HttpRequest req = HttpRequest.newBuilder(URI.create(remote.api() + "/repos/" + owner + "/" + repo + "/pulls"))
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuth(remote.user(), remote.password()))
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload)))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return om.readTree(resp.body()).path("html_url").asText(null);
            }
            throw new IOException("Creazione PR fallita (HTTP " + resp.statusCode() + "): "
                    + resp.body().substring(0, Math.min(300, resp.body().length())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Creazione PR interrotta");
        }
    }

    @Override
    public String findOpenPullForDocument(String document) throws IOException {
        String prefix = "candidate/" + document + "/";
        HttpRequest req = HttpRequest.newBuilder(URI.create(remote.api() + "/repos/" + ownerRepo() + "/pulls?state=open"))
                .header("Authorization", basicAuth(remote.user(), remote.password()))
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

    /** "http://host/owner/repo.git" → "owner/repo" (dal remote git, unica fonte). */
    private String ownerRepo() {
        return remote.url().replaceFirst("\\.git$", "").replaceFirst("^https?://[^/]+/", "");
    }

    private static String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
