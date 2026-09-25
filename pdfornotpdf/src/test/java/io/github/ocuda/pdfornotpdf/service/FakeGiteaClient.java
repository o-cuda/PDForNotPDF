package io.github.ocuda.pdfornotpdf.service;

import java.io.IOException;

/**
 * Fake di {@link GiteaClient} per i test unit del flusso candidate→PR (audit 05 / T18):
 * nessuna rete, comportamento pilotabile dai test (PR aperta già esistente, errore di creazione).
 */
class FakeGiteaClient implements GiteaClient {

    boolean enabled = true;
    /** URL della PR aperta restituito da findOpenPullForDocument (null = nessuna). */
    String openPullUrl = null;
    /** Se non-null, createPullRequest fallisce con questo messaggio. */
    String failOnCreateMessage = null;
    String prUrl = "http://fake/pr/1";

    int createCalls = 0;
    String lastDocument;
    int lastVersion;
    String lastBranch;
    String lastNote;
    String lastAuthor;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String createPullRequest(String document, int version, String branch, String note, String author) throws IOException {
        createCalls++;
        lastDocument = document;
        lastVersion = version;
        lastBranch = branch;
        lastNote = note;
        lastAuthor = author;
        if (failOnCreateMessage != null) {
            throw new IOException(failOnCreateMessage);
        }
        return prUrl;
    }

    @Override
    public String findOpenPullForDocument(String document) throws IOException {
        return openPullUrl;
    }
}
