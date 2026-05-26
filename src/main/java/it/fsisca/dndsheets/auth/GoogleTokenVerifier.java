package it.fsisca.dndsheets.auth;

/**
 * Verifica un ID token OIDC emesso da Google e ne estrae il payload.
 *
 * <p>Astratto via interfaccia per consentire mock nei test: l'implementazione
 * reale ({@link GoogleTokenVerifierImpl}) scarica la JWKS di Google e valida
 * firma/iss/aud/exp; i test forniscono un fake che ritorna un payload
 * predefinito senza chiamare la rete.</p>
 */
public interface GoogleTokenVerifier {

    /**
     * Verifica {@code idToken}. Lancia {@link InvalidGoogleTokenException} se
     * la firma e' invalida, l'audience non corrisponde al nostro client ID,
     * l'issuer non e' Google, o il token e' scaduto.
     */
    GoogleIdentity verify(String idToken) throws InvalidGoogleTokenException;

    /** Eccezione lanciata su qualsiasi tipo di token non valido. */
    class InvalidGoogleTokenException extends RuntimeException {
        public InvalidGoogleTokenException(String message) { super(message); }
        public InvalidGoogleTokenException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Identita' estratta da un ID token Google valido. {@code sub} e' lo
     * Stable user identifier dell'account Google; {@code email} e' sempre
     * presente perche' lo scope {@code email} e' obbligatorio nella nostra
     * configurazione; {@code emailVerified} e' true per gli account Google
     * standard (Google verifica le email al signup).
     */
    record GoogleIdentity(
            String sub,
            String email,
            boolean emailVerified,
            String name,
            String picture
    ) {}
}
