package it.fsisca.dndsheets.auth;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Utente registrato. Active Record stile Panache:
 * usare i metodi statici (es. {@link #findByEmail}) per le query.
 */
@MongoEntity(collection = "users")
public class User extends PanacheMongoEntity {

    // ----- Tier costanti (String per stabilità BSON) -----
    public static final String TIER_FREE    = "FREE";
    public static final String TIER_PREMIUM = "PREMIUM";

    public static final String SRC_IAP_GOOGLE  = "IAP_GOOGLE";
    public static final String SRC_IAP_APPLE   = "IAP_APPLE";
    public static final String SRC_STRIPE      = "STRIPE";
    public static final String SRC_ADMIN_GRANT = "ADMIN_GRANT";

    public static final String ROLE_ADMIN = "ADMIN";

    public String email;
    /**
     * Hash Argon2id della password. Null per utenti registrati via Google
     * (OAuth-only) che non hanno mai impostato una password.
     */
    public String passwordHash;
    public boolean emailVerified;
    public String username;
    public String displayName;
    public String bio;
    public org.bson.types.ObjectId avatarFileId;   // riferimento GridFS, null se non c'e'

    /**
     * "sub" del token OIDC Google (identificatore stabile dell'account Google,
     * indipendente dalla email). Univoco fra gli utenti. Null per utenti che
     * non hanno mai fatto login con Google.
     */
    public String googleSub;

    public Instant createdAt;
    public Instant updatedAt;
    public Instant deletedAt;       // soft delete; null = attivo

    /**
     * Timestamp di accettazione di Privacy Policy e Termini di Servizio al
     * momento della registrazione (proof of consent ex art. 7(1) GDPR).
     * Null per utenti pre-esistenti registrati prima dell'introduzione del
     * checkbox; sara' richiesto a tutti i nuovi account.
     */
    public Instant privacyAcceptedAt;

    // ----- Monetizzazione / ruoli -----

    /** {@link #TIER_FREE} o {@link #TIER_PREMIUM}. Mai null sui nuovi utenti. */
    public String tier;
    /** Quando l'utente e' diventato premium. Null se FREE. */
    public Instant premiumSince;
    /** Canale che ha generato il premium: IAP_GOOGLE/IAP_APPLE/STRIPE/ADMIN_GRANT. Null se FREE. */
    public String premiumSource;
    /** Ruoli applicativi. Per ora solo "ADMIN". Set vuoto per utente normale. */
    public Set<String> roles;

    public boolean isPremium() {
        return TIER_PREMIUM.equals(tier);
    }

    public boolean isAdmin() {
        return roles != null && roles.contains(ROLE_ADMIN);
    }

    /** Inizializza i default per un nuovo utente. */
    public void applyDefaults() {
        if (tier == null) tier = TIER_FREE;
        if (roles == null) roles = new HashSet<>();
    }

    // ----- Query helpers -----

    public static Optional<User> findByEmail(String email) {
        return Optional.ofNullable(find("email", email).firstResult());
    }

    public static Optional<User> findByUsername(String username) {
        return Optional.ofNullable(find("username", username).firstResult());
    }

    public static Optional<User> findByGoogleSub(String googleSub) {
        return Optional.ofNullable(find("googleSub", googleSub).firstResult());
    }

    public static boolean existsByEmail(String email) {
        return count("email", email) > 0;
    }

    public static boolean existsByUsername(String username) {
        return count("username", username) > 0;
    }
}
