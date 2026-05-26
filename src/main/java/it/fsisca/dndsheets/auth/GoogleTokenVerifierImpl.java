package it.fsisca.dndsheets.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Optional;

/**
 * Implementazione di default di {@link GoogleTokenVerifier} basata sulla
 * libreria {@code google-api-client}. La libreria si occupa di:
 * <ul>
 *   <li>scaricare e cachare la JWKS di Google ({@code googleapis.com/oauth2/v3/certs}),</li>
 *   <li>verificare firma, issuer ({@code accounts.google.com}), audience
 *       (il nostro Client ID Web) e scadenza,</li>
 *   <li>parsare il payload OIDC standard.</li>
 * </ul>
 *
 * <p>Se la property {@code app.google.client-id} e' vuota, il verifier alza
 * sempre {@link InvalidGoogleTokenException} con codice {@code "NOT_CONFIGURED"},
 * cosi' l'endpoint risponde 503 invece di silenziosamente accettare tutto.</p>
 */
@ApplicationScoped
public class GoogleTokenVerifierImpl implements GoogleTokenVerifier {

    private static final Logger LOG = Logger.getLogger(GoogleTokenVerifierImpl.class);

    /**
     * Optional perche' SmallRye Config in Quarkus 3.x rifiuta di iniettare
     * una String quando l'env var risolve a "" (es. {@code GOOGLE_CLIENT_ID_WEB}
     * non impostata in prod): il startup fallisce con "Failed to load config
     * value of type class java.lang.String". Con Optional&lt;String&gt; il valore
     * mancante e' un Optional.empty() lecito.
     */
    @ConfigProperty(name = "app.google.client-id")
    Optional<String> clientIdConfig;

    private GoogleIdTokenVerifier verifier;

    @PostConstruct
    void init() {
        final String id = clientIdConfig.orElse("").trim();
        if (id.isEmpty()) {
            LOG.info("app.google.client-id non configurata: POST /auth/google rispondera' 503.");
            return;
        }
        this.verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(id))
                .build();
        LOG.infof("GoogleTokenVerifier inizializzato per audience %s", maskClientId(id));
    }

    @Override
    public GoogleIdentity verify(String idToken) throws InvalidGoogleTokenException {
        if (verifier == null) {
            throw new InvalidGoogleTokenException("NOT_CONFIGURED");
        }
        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException e) {
            throw new InvalidGoogleTokenException("Errore nella verifica del token Google", e);
        }
        if (token == null) {
            // verifier.verify() ritorna null se firma/audience/issuer/exp non passano.
            throw new InvalidGoogleTokenException("ID token Google non valido");
        }

        GoogleIdToken.Payload p = token.getPayload();
        String sub   = p.getSubject();
        String email = p.getEmail();
        if (sub == null || email == null) {
            throw new InvalidGoogleTokenException("Token Google senza sub/email");
        }
        boolean emailVerified = Optional.ofNullable(p.getEmailVerified()).orElse(Boolean.FALSE);
        String name    = (String) p.get("name");
        String picture = (String) p.get("picture");
        return new GoogleIdentity(sub, email, emailVerified, name, picture);
    }

    /** Maschera il client ID per il log (mostra solo i primi 8 char + suffisso). */
    private static String maskClientId(String s) {
        if (s.length() <= 12) return "***";
        return s.substring(0, 8) + "..." + s.substring(s.length() - 4);
    }
}
