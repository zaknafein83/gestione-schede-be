package it.fsisca.dndsheets.dice;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Tiro di dadi salvato per cronologia. TTL su {@code expiresAt}.
 * Il backend e' "dumb storage": riceve dal frontend la formula e il risultato
 * gia' calcolato (parser + RNG vivono lato client per essere immediati e per
 * non pagare un round-trip per ogni tiro).
 */
@MongoEntity(collection = "dice_rolls")
public class DiceRoll extends PanacheMongoEntity {

    public ObjectId ownerId;
    /** Scheda associata al tiro; null = tiro globale (non legato a una scheda). */
    public ObjectId characterId;

    public String formula;
    public int    total;
    public String breakdown;     // gia' formattato (per display)
    public boolean advantage;
    public boolean disadvantage;

    public Instant createdAt;
    /** TTL: Mongo cancella automaticamente i documenti scaduti. */
    public Instant expiresAt;
}
