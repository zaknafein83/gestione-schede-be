package it.fsisca.dndsheets.share;

import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.character.Character;
import it.fsisca.dndsheets.character.CharacterService;
import it.fsisca.dndsheets.common.AppException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Gestisce i token di condivisione read-only delle schede. Una scheda puo'
 * avere al massimo 1 token attivo per volta: creandone uno nuovo si revoca
 * il precedente (semplifica la UI client e il revoke).
 */
@ApplicationScoped
public class ShareService {

    private static final Logger LOG = Logger.getLogger(ShareService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    @Inject CharacterService characterService;

    public record CreateResult(ShareToken token, String plain) {}

    /** Crea un nuovo token (revocando i precedenti attivi della scheda). */
    public CreateResult create(User owner, String characterId) {
        Character c = characterService.get(owner, characterId);

        // revoca tutti i precedenti ancora attivi
        Instant now = Instant.now();
        ShareToken.<ShareToken>find("characterId = ?1 and revokedAt is null", c.id)
                .list()
                .forEach(t -> {
                    t.revokedAt = now;
                    t.update();
                });

        String plain = generateToken();
        ShareToken t = new ShareToken();
        t.characterId = c.id;
        t.ownerId     = owner.id;
        t.tokenHash   = sha256Hex(plain);
        t.createdAt   = now;
        t.persist();

        LOG.infof("Share token creato per scheda %s (owner=%s)", c.id, owner.email);
        return new CreateResult(t, plain);
    }

    /** Lista i token (attivi e revocati) di una scheda dell'owner. */
    public List<ShareToken> listForCharacter(User owner, String characterId) {
        Character c = characterService.get(owner, characterId);
        return ShareToken.<ShareToken>find(
                "characterId = ?1", c.id).list();
    }

    /** Revoca un token specifico della scheda dell'owner. Idempotente. */
    public void revoke(User owner, String characterId, String shareId) {
        Character c = characterService.get(owner, characterId);
        if (!ObjectId.isValid(shareId)) {
            throw AppException.notFound("SHARE_NOT_FOUND", "Token non trovato");
        }
        ShareToken t = ShareToken.<ShareToken>findById(new ObjectId(shareId));
        if (t == null || !c.id.equals(t.characterId)) {
            throw AppException.notFound("SHARE_NOT_FOUND", "Token non trovato");
        }
        if (t.revokedAt == null) {
            t.revokedAt = Instant.now();
            t.update();
        }
    }

    /**
     * Risolve il token in chiaro alla scheda associata (per la pagina pubblica).
     * 404 se il token non esiste o e' revocato.
     */
    public Character resolveTokenPublic(String tokenPlain) {
        if (tokenPlain == null || tokenPlain.isBlank()) {
            throw AppException.notFound("SHARE_NOT_FOUND", "Token non trovato");
        }
        String hash = sha256Hex(tokenPlain);
        ShareToken t = ShareToken.findByTokenHash(hash)
                .orElseThrow(() -> AppException.notFound("SHARE_NOT_FOUND",
                        "Token non trovato"));
        if (!t.isUsable()) {
            throw AppException.notFound("SHARE_NOT_FOUND", "Token non trovato");
        }
        Character c = Character.<Character>findById(t.characterId);
        if (c == null) {
            throw AppException.notFound("SHARE_NOT_FOUND", "Scheda non disponibile");
        }
        return c;
    }

    // ----- helpers -----

    private static String generateToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }
}
