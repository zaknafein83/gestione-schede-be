package it.fsisca.dndsheets.profile;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.character.Character;
import it.fsisca.dndsheets.dice.DiceRoll;
import it.fsisca.dndsheets.share.ShareToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Costruisce un export completo dei dati personali di un utente per
 * soddisfare il diritto di portabilità (art. 20 GDPR) e accesso (art. 15
 * GDPR). L'output è una mappa serializzabile come JSON.
 *
 * <p>Includiamo:
 * <ul>
 *   <li>account: profilo utente (eccetto passwordHash e avatarFileId opaco)</li>
 *   <li>characters: tutte le schede personaggio dell'utente</li>
 *   <li>diceRolls: cronologia dei tiri di dado non ancora scaduti via TTL</li>
 *   <li>shareTokens: token di condivisione (solo metadata; il valore in
 *       chiaro non è recuperabile perché in DB c'è solo l'hash)</li>
 *   <li>_meta: timestamp dell'export, note legali</li>
 * </ul>
 *
 * <p>Escludiamo per design:
 * <ul>
 *   <li>passwordHash: dato sensibile, mai esportato</li>
 *   <li>refresh tokens: secret di sessione, non sono "dati personali" portabili</li>
 *   <li>email_verifications e password_reset_tokens: token temporanei, vita breve</li>
 *   <li>admin_actions: riguardano operazioni amministrative su altri utenti</li>
 *   <li>contenuti binari (avatar, portrait): non inseriti nel JSON; vengono
 *       segnalati nelle note dell'export con indicazione di come scaricarli
 *       separatamente.</li>
 * </ul>
 */
@ApplicationScoped
public class ExportService {

    private static final Logger LOG = Logger.getLogger(ExportService.class);

    @Inject MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    /**
     * Genera la mappa di export per l'utente specificato.
     * Tutte le ObjectId sono serializzate come String esadecimale, tutti gli
     * Instant come stringa ISO-8601. L'ordine delle chiavi è stabile per
     * facilitare il diff tra export successivi (LinkedHashMap).
     */
    public Map<String, Object> buildExport(User user) {
        LOG.infof("Export dati richiesto da utente %s", user.email);

        Map<String, Object> root = new LinkedHashMap<>();

        // ---- _meta ----
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generatedAt",   Instant.now().toString());
        meta.put("schemaVersion", "1");
        meta.put("note",
                "Questo file contiene i tuoi dati personali esportati ai sensi degli articoli 15 e 20 "
              + "del Regolamento (UE) 2016/679 (GDPR). I contenuti binari (avatar del profilo e ritratti "
              + "delle schede) NON sono inclusi in questo JSON: puoi scaricarli separatamente dalle "
              + "rispettive sezioni dell'app prima di esercitare l'eventuale cancellazione dell'account. "
              + "Le password sono memorizzate solo in forma di hash crittografico e non sono esportate.");
        root.put("_meta", meta);

        // ---- account ----
        root.put("account", accountToMap(user));

        // ---- characters ----
        List<Character> chars = Character.<Character>find("ownerId", user.id).list();
        List<Map<String, Object>> charactersJson = new ArrayList<>(chars.size());
        for (Character c : chars) {
            charactersJson.add(characterToMap(c));
        }
        root.put("characters", charactersJson);

        // ---- diceRolls ----
        List<DiceRoll> rolls = DiceRoll.<DiceRoll>find("ownerId", user.id).list();
        List<Map<String, Object>> diceJson = new ArrayList<>(rolls.size());
        for (DiceRoll r : rolls) {
            diceJson.add(diceRollToMap(r));
        }
        root.put("diceRolls", diceJson);

        // ---- shareTokens (solo metadata, NO il token in chiaro) ----
        List<ShareToken> shares = ShareToken.<ShareToken>find("ownerId", user.id).list();
        List<Map<String, Object>> sharesJson = new ArrayList<>(shares.size());
        for (ShareToken s : shares) {
            sharesJson.add(shareTokenToMap(s));
        }
        root.put("shareTokens", sharesJson);

        return root;
    }

    // ------------------------------------------------------------------
    //                          Mappatori
    // ------------------------------------------------------------------

    private Map<String, Object> accountToMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                 toStringId(u.id));
        m.put("email",              u.email);
        m.put("emailVerified",      u.emailVerified);
        m.put("username",           u.username);
        m.put("displayName",        u.displayName);
        m.put("bio",                u.bio);
        m.put("hasAvatar",          u.avatarFileId != null);
        m.put("tier",               u.tier);
        m.put("premiumSince",       toIso(u.premiumSince));
        m.put("premiumSource",      u.premiumSource);
        m.put("roles",              u.roles == null ? List.of() : new ArrayList<>(u.roles));
        m.put("createdAt",          toIso(u.createdAt));
        m.put("updatedAt",          toIso(u.updatedAt));
        m.put("privacyAcceptedAt",  toIso(u.privacyAcceptedAt));
        m.put("ageDeclaredAt",      toIso(u.ageDeclaredAt));
        return m;
    }

    /**
     * Per le schede serializziamo via toJson() di Mongo per non dover mappare a
     * mano ~50 campi: convertiamo la entity in BsonDocument tramite il driver e
     * poi in Map. Questo cattura automaticamente anche eventuali campi futuri.
     * Escludiamo {@code portraitFileId} dal JSON (opaco) e lo sostituiamo con
     * {@code hasPortrait}.
     */
    private Map<String, Object> characterToMap(Character c) {
        // Recuperiamo il Document raw dalla collection per avere già la
        // serializzazione "amichevole" che Panache usa internamente
        Document raw = mongoClient.getDatabase(dbName)
                .getCollection("characters")
                .find(Filters.eq("_id", c.id))
                .first();
        Map<String, Object> m;
        if (raw != null) {
            m = new LinkedHashMap<>(raw);
            // Sostituiamo ObjectId con String per JSON-friendliness
            replaceObjectId(m, "_id");
            replaceObjectId(m, "ownerId");
            // portrait: non includiamo l'ObjectId del file (opaco), solo il flag
            boolean hasPortrait = m.remove("portraitFileId") != null;
            m.put("hasPortrait", hasPortrait);
        } else {
            // Fallback: shouldn't happen, ma se accade non rompiamo l'export
            m = new LinkedHashMap<>();
            m.put("id",   toStringId(c.id));
            m.put("name", c.name);
            m.put("_note", "Lettura raw fallita, dati incompleti");
        }
        return m;
    }

    private Map<String, Object> diceRollToMap(DiceRoll r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            toStringId(r.id));
        m.put("characterId",   toStringId(r.characterId));
        m.put("formula",       r.formula);
        m.put("total",         r.total);
        m.put("breakdown",     r.breakdown);
        m.put("advantage",     r.advantage);
        m.put("disadvantage",  r.disadvantage);
        m.put("createdAt",     toIso(r.createdAt));
        m.put("expiresAt",     toIso(r.expiresAt));
        return m;
    }

    private Map<String, Object> shareTokenToMap(ShareToken s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          toStringId(s.id));
        m.put("characterId", toStringId(s.characterId));
        m.put("createdAt",   toIso(s.createdAt));
        m.put("revokedAt",   toIso(s.revokedAt));
        m.put("active",      s.isUsable());
        m.put("_note", "Il valore in chiaro del token di condivisione non è recuperabile: "
                + "in database conserviamo solo l'hash SHA-256. Per generare un nuovo link "
                + "di condivisione, usa la funzione Condividi nell'app.");
        return m;
    }

    // ------------------------------------------------------------------
    //                          Helpers
    // ------------------------------------------------------------------

    private static String toStringId(ObjectId id) {
        return id == null ? null : id.toHexString();
    }

    private static String toIso(Instant i) {
        return i == null ? null : i.toString();
    }

    private static void replaceObjectId(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof ObjectId oid) {
            m.put(key, oid.toHexString());
        }
    }
}
