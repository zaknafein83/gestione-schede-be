package it.fsisca.dndsheets.auth;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Optional;

/**
 * Token per la verifica dell'indirizzo email.
 * In DB salviamo SOLO l'hash del token (SHA-256), mai il token in chiaro:
 * il token in chiaro viaggia solo nel link che l'utente riceve via email.
 */
@MongoEntity(collection = "email_verifications")
public class EmailVerificationToken extends PanacheMongoEntity {

    public ObjectId userId;
    public String tokenHash;    // SHA-256 del token in chiaro, hex-encoded
    public Instant createdAt;
    public Instant expiresAt;
    public Instant usedAt;      // null finche' non e' stato usato

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public static Optional<EmailVerificationToken> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(find("tokenHash", tokenHash).firstResult());
    }
}
