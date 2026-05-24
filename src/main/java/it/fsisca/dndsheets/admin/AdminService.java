package it.fsisca.dndsheets.admin;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import it.fsisca.dndsheets.admin.dto.AdminUserPage;
import it.fsisca.dndsheets.admin.dto.AdminUserSummary;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.profile.ProfileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Logica admin: ricerca utenti, grant/revoke premium, eliminazione account.
 * Tutti i metodi presuppongono che il chiamante sia gia' stato autorizzato
 * a livello di endpoint (via @RolesAllowed).
 */
@ApplicationScoped
public class AdminService {

    private static final Logger LOG = Logger.getLogger(AdminService.class);
    private static final int MAX_PAGE_SIZE = 100;

    @Inject ProfileService profileService;

    /**
     * Ricerca paginata. {@code q} (opzionale) filtra per email/username/displayName
     * case-insensitive. Ordine: createdAt desc.
     */
    public AdminUserPage listUsers(String q, int page, int pageSize) {
        int pIdx = Math.max(0, page);
        int pSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        String trimmed = q == null ? "" : q.trim();
        long total;
        List<User> items;
        Sort sort = Sort.descending("createdAt");

        if (trimmed.isEmpty()) {
            total = User.count();
            items = User.<User>findAll(sort).page(Page.of(pIdx, pSize)).list();
        } else {
            // Match case-insensitive su email/username/displayName via regex
            String regex = ".*" + Pattern.quote(trimmed) + ".*";
            Map<String, Object> params = new HashMap<>();
            params.put("re", regex);
            String query = "{ '$or': [ "
                    + "{ 'email':       { '$regex': :re, '$options': 'i' } },"
                    + "{ 'username':    { '$regex': :re, '$options': 'i' } },"
                    + "{ 'displayName': { '$regex': :re, '$options': 'i' } }"
                    + "] }";
            total = User.count(query, params);
            items = User.<User>find(query, sort, params).page(Page.of(pIdx, pSize)).list();
        }

        return new AdminUserPage(
                items.stream().map(AdminUserSummary::from).toList(),
                total, pIdx, pSize);
    }

    public AdminUserSummary grantPremium(User admin, String targetId) {
        User target = loadTarget(targetId);
        if (target.isPremium()) {
            // Idempotente: ritorna lo stato senza errore
            return AdminUserSummary.from(target);
        }
        Instant now = Instant.now();
        target.tier          = User.TIER_PREMIUM;
        target.premiumSince  = now;
        target.premiumSource = User.SRC_ADMIN_GRANT;
        target.updatedAt     = now;
        target.update();

        logAction(admin, target, AdminAction.ACTION_GRANT_PREMIUM,
                Map.of("source", User.SRC_ADMIN_GRANT));
        LOG.infof("Admin %s ha concesso premium a %s", admin.email, target.email);
        return AdminUserSummary.from(target);
    }

    public AdminUserSummary revokePremium(User admin, String targetId) {
        User target = loadTarget(targetId);
        if (!target.isPremium()) {
            return AdminUserSummary.from(target);
        }
        Instant now = Instant.now();
        target.tier          = User.TIER_FREE;
        target.premiumSince  = null;
        target.premiumSource = null;
        target.updatedAt     = now;
        target.update();

        logAction(admin, target, AdminAction.ACTION_REVOKE_PREMIUM, Map.of());
        LOG.infof("Admin %s ha revocato premium a %s", admin.email, target.email);
        return AdminUserSummary.from(target);
    }

    /**
     * Cancella un utente con cascade — riusa la stessa logica della
     * self-deletion (ProfileService.deleteAccountByAdmin) ma senza richiesta password.
     * Un admin non puo' cancellare se stesso (per evitare lockout).
     */
    public void deleteUser(User admin, String targetId) {
        User target = loadTarget(targetId);
        if (target.id.equals(admin.id)) {
            throw AppException.forbidden("CANNOT_DELETE_SELF",
                    "Un admin non puo' cancellare il proprio account dall'admin panel. "
                            + "Usa la cancellazione account dal profilo.");
        }
        String email = target.email;
        ObjectId targetIdObj = target.id;

        profileService.deleteAccountCascade(target);

        // Audit dopo cancellazione: il targetEmail e' ancora memorizzato qui
        logAction(admin, email, targetIdObj, AdminAction.ACTION_DELETE_USER, Map.of());
        LOG.infof("Admin %s ha cancellato l'utente %s", admin.email, email);
    }

    // ----- helpers -----

    private User loadTarget(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw AppException.notFound("USER_NOT_FOUND", "Utente non trovato");
        }
        return User.<User>findByIdOptional(new ObjectId(id))
                .orElseThrow(() -> AppException.notFound("USER_NOT_FOUND",
                        "Utente non trovato"));
    }

    private void logAction(User admin, User target, String action, Map<String, Object> payload) {
        logAction(admin, target.email, target.id, action, payload);
    }

    private void logAction(User admin, String targetEmail, ObjectId targetUserId,
                           String action, Map<String, Object> payload) {
        AdminAction a = new AdminAction();
        a.adminId       = admin.id;
        a.adminEmail    = admin.email;
        a.action        = action;
        a.targetUserId  = targetUserId;
        a.targetEmail   = targetEmail;
        a.payload       = payload == null ? new HashMap<>() : new HashMap<>(payload);
        a.at            = Instant.now();
        a.persist();
    }

}
