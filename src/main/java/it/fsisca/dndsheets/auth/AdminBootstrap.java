package it.fsisca.dndsheets.auth;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Optional;

/**
 * Promuove ad ADMIN l'utente con l'email indicata in
 * {@code app.admin-bootstrap-email} al primo avvio. Idempotente: se l'utente
 * non esiste ancora o ha gia' il ruolo, no-op. Lasciare la property vuota
 * per disabilitare (default).
 *
 * <p>Note: l'utente DEVE essersi gia' registrato prima che il bootstrap abbia
 * effetto. Lo startup gira a ogni boot, quindi e' sufficiente registrarsi e
 * riavviare l'app una volta.</p>
 */
@Startup(value = 20)   // dopo MongoIndexes (priority default = 10)
@ApplicationScoped
public class AdminBootstrap {

    private static final Logger LOG = Logger.getLogger(AdminBootstrap.class);

    @Inject
    @ConfigProperty(name = "app.admin-bootstrap-email")
    Optional<String> bootstrapEmail;

    @PostConstruct
    void init() {
        String email = bootstrapEmail.orElse("").trim().toLowerCase();
        if (email.isEmpty()) {
            LOG.debug("AdminBootstrap: property vuota, nessun admin da promuovere");
            return;
        }

        Optional<User> opt = User.findByEmail(email);
        if (opt.isEmpty()) {
            LOG.infof("AdminBootstrap: utente %s non trovato (ancora). "
                    + "Registralo e riavvia l'app per la promozione.", email);
            return;
        }

        User u = opt.get();
        if (u.roles == null) u.roles = new HashSet<>();
        if (u.roles.contains(User.ROLE_ADMIN)) {
            LOG.debugf("AdminBootstrap: %s e' gia' admin, no-op", email);
            return;
        }
        u.roles.add(User.ROLE_ADMIN);
        u.update();
        LOG.infof("AdminBootstrap: promosso %s ad ADMIN", email);
    }
}
