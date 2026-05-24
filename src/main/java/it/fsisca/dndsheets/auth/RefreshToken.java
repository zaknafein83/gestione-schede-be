package it.fsisca.dndsheets.auth;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Optional;

/**
 * Refresh token long-lived (30 giorni). In DB salviamo SOLO l'hash SHA-256
 * del token in chiaro: il client conserva il chiaro e ce lo manda per ottenere
 * un nuovo access token.
 */
@MongoEntity(collection = "refresh_tokens")
public class RefreshToken extends PanacheMongoEntity {

    public ObjectId userId;
    public String tokenHash;    // SHA-256 hex
    public Instant createdAt;
    public Instant expiresAt;
    public Instant revokedAt;   // null = ancora valido

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public static Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(find("tokenHash", tokenHash).firstResult());
    }
}
