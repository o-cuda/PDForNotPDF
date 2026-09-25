package io.github.ocuda.pdfornotpdf.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configurazione del remote Gitea in UN SOLO punto (E-Proposte/06 / K4).
 * Prima era bindata via @Value in tre bean (ReleaseService, GitOperations, HttpGiteaClient):
 * tre copie = rischio di drift al primo cambio di config.
 */
@Component
public class RemoteConfig {

    private final String url;
    private final String api;
    private final String user;
    private final String password;
    private final boolean prAuto;

    public RemoteConfig(
            @Value("${app.release.remote.url:}") String url,
            @Value("${app.release.remote.api:}") String api,
            @Value("${app.release.remote.user:}") String user,
            @Value("${app.release.remote.password:}") String password,
            @Value("${app.release.remote.pr-auto:true}") boolean prAuto) {
        this.url = url;
        this.api = api;
        this.user = user;
        this.password = password;
        this.prAuto = prAuto;
    }

    /** Remote git (es. http://host/owner/repo.git o bare repo locale). */
    public String url() {
        return url;
    }

    /** Base URL delle API REST della forge. */
    public String api() {
        return api;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /** true se le candidate devono aprire PR automaticamente. */
    public boolean prAuto() {
        return prAuto;
    }

    /** true se il remote è configurato (url non vuoto). */
    public boolean enabled() {
        return url != null && !url.isBlank();
    }
}
