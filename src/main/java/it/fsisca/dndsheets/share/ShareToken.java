package it.fsisca.dndsheets.share;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Optional;

/**
 * Token di condivisione read-only di una scheda personaggio.
 * In DB salviamo SOLO l'hash SHA-256 del token: il token in chiaro vive
 * nel link che l'owner condivide (mostrato una sola volta lato UI).
 */
@MongoEntity(collection = "share_tokens")
public class ShareToken extends PanacheMongoEntity {

    public ObjectId characterId;
    public ObjectId ownerId;
    public String   tokenHash;     // SHA-256 hex
    public Instant  createdAt;
    public Instant  revokedAt;     // null = attivo

    public boolean isUsable() {
        return revokedAt == null;
    }

    public static Optional<ShareToken> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(find("tokenHash", tokenHash).firstResult());
    }
}
