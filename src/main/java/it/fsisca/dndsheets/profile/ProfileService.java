package it.fsisca.dndsheets.profile;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import it.fsisca.dndsheets.auth.PasswordHasher;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.character.Character;
import it.fsisca.dndsheets.character.CharacterPortraitService;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.profile.dto.ChangePasswordRequest;
import it.fsisca.dndsheets.profile.dto.UpdateMeRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Logica del profilo utente: update parziale e cambio password.
 */
@ApplicationScoped
public class ProfileService {

    private static final Logger LOG = Logger.getLogger(ProfileService.class);

    @Inject PasswordHasher            passwordHasher;
    @Inject MongoClient               mongoClient;
    @Inject AvatarService             avatarService;
    @Inject CharacterPortraitService  portraitService;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    /** Aggiorna solo i campi non-null del payload. */
    public User updateMe(User user, UpdateMeRequest req) {
        boolean changed = false;
        if (req.displayName() != null) { user.displayName = req.displayName().trim(); changed = true; }
        if (req.bio() != null)         { user.bio         = req.bio().trim();         changed = true; }

        if (changed) {
            user.updatedAt = Instant.now();
            user.update();
        }
        return user;
    }

    /**
     * Verifica password attuale, hash della nuova e revoca tutti i refresh
     * token dell'utente (best practice: dopo cambio password l'utente deve
     * rifare login su tutti i device).
     */
    public void changePassword(User user, ChangePasswordRequest req) {
        // Account social-only (registrati via Google): passwordHash e' null e
        // verify() lancerebbe. Diamo un errore parlante invece di un 500.
        if (user.passwordHash == null) {
            throw AppException.badRequest("NO_PASSWORD_SET",
                    "Il tuo account non ha una password (accesso con Google). "
                    + "Usa il login Google per accedere.");
        }
        if (!passwordHasher.verify(req.currentPassword(), user.passwordHash)) {
            throw AppException.unauthorized("INVALID_CURRENT_PASSWORD",
                    "La password attuale non e' corretta");
        }
        if (req.currentPassword().equals(req.newPassword())) {
            throw AppException.badRequest("SAME_PASSWORD",
                    "La nuova password deve essere diversa da quella attuale");
        }

        user.passwordHash = passwordHasher.hash(req.newPassword());
        user.updatedAt    = Instant.now();
        user.update();

        // Revoca tutti i refresh token attivi dell'utente
        long now = Instant.now().toEpochMilli();
        var result = mongoClient.getDatabase(dbName)
                .getCollection("refresh_tokens")
                .updateMany(
                        Filters.and(Filters.eq("userId", user.id), Filters.eq("revokedAt", null)),
                        Updates.set("revokedAt", Instant.ofEpochMilli(now)));

        LOG.infof("Password cambiata per %s — %d refresh token revocati",
                user.email, result.getModifiedCount());
    }

    /**
     * Cancella l'account utente con tutti i dati associati. Hard delete
     * irreversibile. Cascade:
     *   1. Verifica password attuale (401 se errata)
     *   2. Per ogni scheda dell'utente: cancella portrait GridFS
     *   3. Cancella tutte le share_tokens delle schede dell'utente
     *   4. Cancella tutti i dice_rolls dell'utente
     *   5. Cancella tutte le schede dell'utente
     *   6. Cancella l'avatar GridFS
     *   7. Cancella tutti i refresh_tokens dell'utente
     *   8. Cancella tutti gli email_verifications dell'utente
     *   9. Cancella tutti i password_reset_tokens dell'utente
     *  10. Cancella l'utente
     */
    public void deleteAccount(User user, String passwordPlain) {
        if (!passwordHasher.verify(passwordPlain, user.passwordHash)) {
            throw AppException.unauthorized("INVALID_PASSWORD",
                    "Password non corretta");
        }
        deleteAccountCascade(user);
    }

    /**
     * Cascade dell'eliminazione account, senza verifica password — pensata
     * per essere chiamata da contesti gia' autorizzati (es. admin panel).
     */
    public void deleteAccountCascade(User user) {
        ObjectId userId = user.id;
        String   email  = user.email;
        var db = mongoClient.getDatabase(dbName);

        // 1. Raccogli ID delle schede + portraitFileId
        List<Character> characters = Character.<Character>find("ownerId", userId).list();
        List<ObjectId>  characterIds  = new ArrayList<>(characters.size());
        List<ObjectId>  portraitFiles = new ArrayList<>();
        for (Character c : characters) {
            characterIds.add(c.id);
            if (c.portraitFileId != null) portraitFiles.add(c.portraitFileId);
        }

        // 2. Cancella i file portrait (GridFS, fuori dalle collection regolari)
        for (ObjectId pid : portraitFiles) {
            portraitService.deleteFileOnly(pid);
        }

        // 3. Share tokens delle schede dell'utente
        long sharesDeleted = 0;
        if (!characterIds.isEmpty()) {
            sharesDeleted = db.getCollection("share_tokens")
                    .deleteMany(Filters.in("characterId", characterIds))
                    .getDeletedCount();
        }
        // anche eventuali share token col solo ownerId (ridondanza per sicurezza)
        sharesDeleted += db.getCollection("share_tokens")
                .deleteMany(Filters.eq("ownerId", userId))
                .getDeletedCount();

        // 4. Dice rolls
        long rollsDeleted = db.getCollection("dice_rolls")
                .deleteMany(Filters.eq("ownerId", userId))
                .getDeletedCount();

        // 5. Characters
        long charsDeleted = db.getCollection("characters")
                .deleteMany(Filters.eq("ownerId", userId))
                .getDeletedCount();

        // 5b. Character layouts (dashboard premium)
        long layoutsDeleted = db.getCollection("character_layouts")
                .deleteMany(Filters.eq("ownerId", userId))
                .getDeletedCount();

        // 6. Avatar GridFS
        avatarService.delete(user);

        // 7. Refresh tokens
        long refreshDeleted = db.getCollection("refresh_tokens")
                .deleteMany(Filters.eq("userId", userId))
                .getDeletedCount();

        // 8. Email verifications
        long evDeleted = db.getCollection("email_verifications")
                .deleteMany(Filters.eq("userId", userId))
                .getDeletedCount();

        // 9. Password reset tokens
        long prtDeleted = db.getCollection("password_reset_tokens")
                .deleteMany(Filters.eq("userId", userId))
                .getDeletedCount();

        // 10. User
        user.delete();

        LOG.infof("Account cancellato: %s — characters=%d, layouts=%d, portraits=%d, shares=%d, "
                        + "rolls=%d, refresh=%d, evt=%d, prt=%d",
                email, charsDeleted, layoutsDeleted, portraitFiles.size(), sharesDeleted,
                rollsDeleted, refreshDeleted, evDeleted, prtDeleted);
    }
}
