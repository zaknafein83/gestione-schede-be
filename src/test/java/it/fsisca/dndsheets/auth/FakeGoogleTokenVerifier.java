package it.fsisca.dndsheets.auth;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stub di {@link GoogleTokenVerifier} attivo in {@code src/test} grazie a
 * {@link Mock}: sovrascrive automaticamente {@link GoogleTokenVerifierImpl}
 * senza dover toccare il classpath di produzione.
 *
 * <p>Lo stato e' statico cosi' i test possono configurare il prossimo esito
 * via {@link #setNext(GoogleTokenVerifier.GoogleIdentity)} oppure
 * {@link #setNextInvalid(String)} prima della chiamata HTTP.</p>
 *
 * <p>NB: nessun altro test colpisce /auth/google, quindi il fake non
 * interferisce con la suite esistente.</p>
 */
@Mock
@ApplicationScoped
public class FakeGoogleTokenVerifier implements GoogleTokenVerifier {

    private static GoogleIdentity nextIdentity;
    private static String         nextError;

    public static void setNext(GoogleIdentity identity) {
        nextIdentity = identity;
        nextError    = null;
    }

    /** Forza la prossima verify() a lanciare {@link InvalidGoogleTokenException}. */
    public static void setNextInvalid(String message) {
        nextIdentity = null;
        nextError    = message;
    }

    /** Resetta lo stato fra un test e l'altro. */
    public static void reset() {
        nextIdentity = null;
        nextError    = null;
    }

    @Override
    public GoogleIdentity verify(String idToken) throws InvalidGoogleTokenException {
        if (nextError != null) {
            throw new InvalidGoogleTokenException(nextError);
        }
        if (nextIdentity == null) {
            throw new InvalidGoogleTokenException("FakeGoogleTokenVerifier: nessuna identita' impostata");
        }
        return nextIdentity;
    }
}
