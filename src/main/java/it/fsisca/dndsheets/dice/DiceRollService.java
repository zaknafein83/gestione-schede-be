package it.fsisca.dndsheets.dice;

import io.quarkus.panache.common.Sort;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.dice.dto.DiceRollPayload;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Cronologia tiri dadi. Il backend non ricalcola: persiste quanto ricevuto.
 * TTL: i documenti vengono rimossi automaticamente da Mongo dopo 30 giorni.
 */
@ApplicationScoped
public class DiceRollService {

    private static final int  DEFAULT_LIMIT = 50;
    private static final int  MAX_LIMIT     = 200;
    private static final long TTL_DAYS      = 30;

    public DiceRoll create(User owner, DiceRollPayload p) {
        DiceRoll r = new DiceRoll();
        r.ownerId      = owner.id;
        r.characterId  = parseCharacterIdOrNull(p.characterId());
        r.formula      = p.formula().trim();
        r.total        = p.total();
        r.breakdown    = p.breakdown();
        r.advantage    = p.advantage();
        r.disadvantage = p.disadvantage();
        r.createdAt    = Instant.now();
        r.expiresAt    = r.createdAt.plus(TTL_DAYS, ChronoUnit.DAYS);
        r.persist();
        return r;
    }

    public List<DiceRoll> listForOwner(User owner, String characterId, Integer limit) {
        final int lim = limit == null
                ? DEFAULT_LIMIT
                : Math.min(Math.max(limit, 1), MAX_LIMIT);

        if (characterId == null || characterId.isBlank()) {
            return DiceRoll.<DiceRoll>find("ownerId", Sort.descending("createdAt"), owner.id)
                    .page(0, lim)
                    .list();
        }
        ObjectId cid = parseCharacterIdOrThrow(characterId);
        return DiceRoll.<DiceRoll>find(
                        "ownerId = ?1 and characterId = ?2",
                        Sort.descending("createdAt"),
                        owner.id, cid)
                .page(0, lim)
                .list();
    }

    public long deleteAllForOwner(User owner) {
        return DiceRoll.delete("ownerId", owner.id);
    }

    private ObjectId parseCharacterIdOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        if (!ObjectId.isValid(s)) {
            throw AppException.badRequest("INVALID_CHARACTER_ID", "characterId non valido");
        }
        return new ObjectId(s);
    }

    private ObjectId parseCharacterIdOrThrow(String s) {
        if (!ObjectId.isValid(s)) {
            throw AppException.badRequest("INVALID_CHARACTER_ID", "characterId non valido");
        }
        return new ObjectId(s);
    }
}
