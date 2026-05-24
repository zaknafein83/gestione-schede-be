package it.fsisca.dndsheets.auth;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Optional;

/**
 * Token per il reset password dimenticata.
 * In DB salviamo SOLO l'hash del token (SHA-256); il token in chiaro viaggia
 * solo nel link che l'utente riceve via email.
 *
 * Mongo TTL su {@code expiresAt} rimuove automaticamente i token scaduti.
 */
@MongoEntity(collection = "password_reset_tokens")
public class PasswordResetToken extends PanacheMongoEntity {

    public ObjectId userId;
    public String   tokenHash;   // SHA-256 del token in chiaro, hex-encoded
    public Instant  createdAt;
    public Instant  expiresAt;
    /** null finché non viene consumato. */
    public Instant  usedAt;

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public static Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(find("tokenHash", tokenHash).firstResult());
    }
}
