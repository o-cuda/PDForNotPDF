package io.github.ocuda.pdfornotpdf.service;

import java.io.IOException;

/**
 * Client per la forge Gitea (E-Proposte/04 / R3): interfaccia che rende il flusso
 * candidate→PR→merge testabile in unit con un fake (prima era coperto solo da e2e
 * con Gitea vero, perché il client era concreto dentro ReleaseService).
 */
public interface GiteaClient {

    /** true se il remote Gitea è configurato (URL API presente). */
    boolean isEnabled();

    /**
     * Crea la Pull Request per la candidate del documento.
     *
     * @return l'URL html della PR creata
     */
    String createPullRequest(String document, int version, String branch, String note, String author) throws IOException;

    /**
     * URL della PR aperta per il documento, se esiste (una sola candidata aperta per documento).
     *
     * @return l'URL html, o null se non ce ne sono
     */
    String findOpenPullForDocument(String document) throws IOException;
}
