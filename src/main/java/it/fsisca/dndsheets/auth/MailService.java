package it.fsisca.dndsheets.auth;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Invio email transazionali. Per ora plain text — in futuro template Qute.
 */
@ApplicationScoped
public class MailService {

    private static final Logger LOG = Logger.getLogger(MailService.class);

    @Inject
    Mailer mailer;

    @ConfigProperty(name = "app.frontend.base-url")
    String frontendBaseUrl;

    public void sendVerificationEmail(User user, String tokenPlain) {
        String link = frontendBaseUrl
                + "/verify-email?token="
                + URLEncoder.encode(tokenPlain, StandardCharsets.UTF_8);

        String body = """
                Ciao %s,

                grazie per esserti registrato su DnD Sheets.

                Per attivare il tuo account clicca sul link qui sotto entro 24 ore:
                %s

                Se non hai richiesto la registrazione puoi ignorare questa email.
                """.formatted(user.displayName, link);

        Mail mail = Mail.withText(user.email, "Verifica il tuo indirizzo email", body);
        mailer.send(mail);
        LOG.infof("Mail di verifica inviata a %s (link: %s)", user.email, link);
    }

    /**
     * Email di reset password: contiene un link con il token in chiaro.
     * Inviata sia per richieste valide (utente esistente) sia, opzionalmente,
     * silenziosamente skippata se l'utente non esiste (decisione del chiamante).
     */
    public void sendPasswordResetEmail(User user, String tokenPlain) {
        String link = frontendBaseUrl
                + "/reset-password?token="
                + URLEncoder.encode(tokenPlain, StandardCharsets.UTF_8);

        String body = """
                Ciao %s,

                hai richiesto di reimpostare la password del tuo account DnD Sheets.

                Clicca sul link qui sotto entro 1 ora per impostare una nuova password:
                %s

                Se non hai richiesto il reset, puoi ignorare questa email — la
                tua password attuale resta valida.
                """.formatted(user.displayName, link);

        Mail mail = Mail.withText(user.email, "Reset password DnD Sheets", body);
        mailer.send(mail);
        LOG.infof("Mail di reset password inviata a %s", user.email);
    }
}
