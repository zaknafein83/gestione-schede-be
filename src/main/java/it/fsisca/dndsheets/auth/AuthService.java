package it.fsisca.dndsheets.auth;

import it.fsisca.dndsheets.auth.dto.LoginRequest;
import it.fsisca.dndsheets.auth.dto.LoginResponse;
import it.fsisca.dndsheets.auth.dto.RegisterRequest;
import it.fsisca.dndsheets.auth.dto.UserResponse;
import it.fsisca.dndsheets.common.AppException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Logica applicativa della feature auth (F1): registrazione e verifica email.
 */
@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 32;   // 32 byte -> 43 char base64url

    @Inject PasswordHasher passwordHasher;
    @Inject MailService    mailService;
    @Inject JwtService     jwtService;

    @ConfigProperty(name = "app.email-verification.token-duration")
    Duration tokenDuration;

    @ConfigProperty(name = "app.jwt.refresh-token-duration")
    Duration refreshTokenDuration;

    @ConfigProperty(name = "app.password-reset.token-duration", defaultValue = "PT1H")
    Duration passwordResetDuration;

    public User register(RegisterRequest req) {
        String email    = req.email().trim().toLowerCase();
        String username = req.username().trim();

        if (User.existsByEmail(email)) {
            throw AppException.conflict("EMAIL_ALREADY_USED",
                    "Esiste gia' un account con questa email");
        }
        if (User.existsByUsername(username)) {
            throw AppException.conflict("USERNAME_ALREADY_USED",
                    "Lo username e' gia' in uso");
        }

        Instant now = Instant.now();

        User user = new User();
        user.email         = email;
        user.passwordHash  = passwordHasher.hash(req.password());
        user.emailVerified = false;
        user.username      = username;
        user.displayName   = req.displayName().trim();
        user.createdAt     = now;
        user.updatedAt     = now;
        user.applyDefaults();  // tier=FREE, roles=[]
        user.persist();

        // Genera token in chiaro per il link, salva solo l'hash
        String tokenPlain = generateToken();
        EmailVerificationToken evt = new EmailVerificationToken();
        evt.userId    = user.id;
        evt.tokenHash = sha256Hex(tokenPlain);
        evt.createdAt = now;
        evt.expiresAt = now.plus(tokenDuration);
        evt.persist();

        mailService.sendVerificationEmail(user, tokenPlain);
        LOG.infof("Registrato utente %s (id=%s)", user.email, user.id);
        return user;
    }

    /**
     * Verifica email+password, emette access token e refresh token persistito.
     */
    public LoginResponse login(LoginRequest req) {
        String email = req.email().trim().toLowerCase();

        User user = User.findByEmail(email)
                .orElseThrow(() -> AppException.unauthorized("INVALID_CREDENTIALS",
                        "Credenziali non valide"));

        if (!passwordHasher.verify(req.password(), user.passwordHash)) {
            throw AppException.unauthorized("INVALID_CREDENTIALS", "Credenziali non valide");
        }
        if (!user.emailVerified) {
            throw new AppException(jakarta.ws.rs.core.Response.Status.FORBIDDEN,
                    "EMAIL_NOT_VERIFIED",
                    "Devi prima verificare il tuo indirizzo email");
        }

        String accessToken = jwtService.issueAccessToken(user);
        String refreshPlain = issueRefreshToken(user);

        LOG.infof("Login ok per %s", user.email);
        return new LoginResponse(accessToken, refreshPlain, UserResponse.from(user));
    }

    /**
     * Scambia un refresh token valido con una nuova coppia access+refresh.
     * Strategia: rotation — il vecchio refresh viene revocato.
     */
    public LoginResponse refresh(String refreshPlain) {
        String hash = sha256Hex(refreshPlain);
        RefreshToken stored = RefreshToken.findByTokenHash(hash)
                .orElseThrow(() -> AppException.unauthorized("INVALID_REFRESH_TOKEN",
                        "Refresh token non valido"));

        Instant now = Instant.now();
        if (!stored.isUsable(now)) {
            throw AppException.unauthorized("EXPIRED_OR_REVOKED_REFRESH_TOKEN",
                    "Refresh token scaduto o revocato");
        }

        User user = User.<User>findByIdOptional(stored.userId)
                .orElseThrow(() -> AppException.unauthorized("INVALID_REFRESH_TOKEN",
                        "Utente associato al token non trovato"));

        // rotation: revoca il vecchio e ne emette uno nuovo
        stored.revokedAt = now;
        stored.update();

        String accessToken = jwtService.issueAccessToken(user);
        String newRefreshPlain = issueRefreshToken(user);

        LOG.infof("Refresh ok per %s", user.email);
        return new LoginResponse(accessToken, newRefreshPlain, UserResponse.from(user));
    }

    /** Revoca il refresh token (logout). Idempotente: se gia' revocato non lancia. */
    public void logout(String refreshPlain) {
        String hash = sha256Hex(refreshPlain);
        RefreshToken.findByTokenHash(hash).ifPresent(rt -> {
            if (rt.revokedAt == null) {
                rt.revokedAt = Instant.now();
                rt.update();
            }
        });
    }

    public User verifyEmail(String tokenPlain) {
        String tokenHash = sha256Hex(tokenPlain);
        EmailVerificationToken evt = EmailVerificationToken.findByTokenHash(tokenHash)
                .orElseThrow(() -> AppException.badRequest("INVALID_TOKEN",
                        "Token di verifica non valido"));

        Instant now = Instant.now();
        if (!evt.isUsable(now)) {
            throw AppException.badRequest("EXPIRED_OR_USED_TOKEN",
                    "Il token e' gia' stato usato o e' scaduto");
        }

        User user = User.<User>findByIdOptional(evt.userId)
                .orElseThrow(() -> AppException.notFound("USER_NOT_FOUND",
                        "Utente associato al token non trovato"));

        evt.usedAt = now;
        evt.update();

        user.emailVerified = true;
        user.updatedAt = now;
        user.update();

        LOG.infof("Email verificata per utente %s", user.email);
        return user;
    }

    /**
     * Avvia un reset password: se l'email esiste e l'account è verificato,
     * genera un token e manda la mail. Sempre idempotente — non rivela mai
     * al caller se l'email esiste o meno (anti-enumeration).
     *
     * <p>Politica: se esistono token attivi precedenti per lo stesso utente,
     * vengono invalidati ({@code usedAt} = now) prima di emetterne uno nuovo,
     * così se l'utente ripete la richiesta solo l'ultimo link resta valido.</p>
     */
    public void forgotPassword(String emailRaw) {
        String email = (emailRaw == null) ? "" : emailRaw.trim().toLowerCase();
        if (email.isEmpty()) return;

        var userOpt = User.findByEmail(email);
        if (userOpt.isEmpty()) {
            // Log lato server ma silente lato API
            LOG.infof("forgotPassword per email inesistente: %s (no-op)", email);
            return;
        }
        User user = userOpt.get();
        if (!user.emailVerified) {
            // Stessa policy: non lo riveliamo. Se l'account non è verificato,
            // resetting password non ha senso — l'utente deve prima verificare.
            LOG.infof("forgotPassword per account non verificato: %s (no-op)", email);
            return;
        }

        Instant now = Instant.now();

        // Invalida token attivi precedenti.
        // NB: Panache Mongo non gestisce bene "field = null" in stringa,
        // filtriamo in Java (la lista di token attivi per utente è minima).
        PasswordResetToken.<PasswordResetToken>find("userId", user.id).stream()
                .filter(t -> t.usedAt == null)
                .forEach(t -> { t.usedAt = now; t.update(); });

        String tokenPlain = generateToken();
        PasswordResetToken prt = new PasswordResetToken();
        prt.userId    = user.id;
        prt.tokenHash = sha256Hex(tokenPlain);
        prt.createdAt = now;
        prt.expiresAt = now.plus(passwordResetDuration);
        prt.persist();

        mailService.sendPasswordResetEmail(user, tokenPlain);
        LOG.infof("Reset password avviato per %s", user.email);
    }

    /**
     * Consuma un token di reset e imposta la nuova password.
     * Revoca tutti i refresh token attivi dell'utente (forza re-login su
     * tutti i device, per coerenza con changePassword).
     */
    public void resetPassword(String tokenPlain, String newPassword) {
        String tokenHash = sha256Hex(tokenPlain);
        PasswordResetToken prt = PasswordResetToken.findByTokenHash(tokenHash)
                .orElseThrow(() -> AppException.badRequest("INVALID_TOKEN",
                        "Token di reset non valido"));

        Instant now = Instant.now();
        if (!prt.isUsable(now)) {
            throw AppException.badRequest("EXPIRED_OR_USED_TOKEN",
                    "Il token è già stato usato o è scaduto");
        }

        User user = User.<User>findByIdOptional(prt.userId)
                .orElseThrow(() -> AppException.notFound("USER_NOT_FOUND",
                        "Utente associato al token non trovato"));

        // Applica nuova password
        user.passwordHash = passwordHasher.hash(newPassword);
        user.updatedAt    = now;
        user.update();

        // Consuma il token
        prt.usedAt = now;
        prt.update();

        // Revoca TUTTI i refresh token dell'utente (forza logout su tutti i device)
        RefreshToken.<RefreshToken>find("userId", user.id).stream()
                .filter(rt -> rt.revokedAt == null)
                .forEach(rt -> { rt.revokedAt = now; rt.update(); });

        LOG.infof("Password reset completato per %s", user.email);
    }

    /** Genera un refresh token, ne persiste l'hash e ritorna il valore in chiaro. */
    private String issueRefreshToken(User user) {
        String plain = generateToken();
        Instant now = Instant.now();
        RefreshToken rt = new RefreshToken();
        rt.userId    = user.id;
        rt.tokenHash = sha256Hex(plain);
        rt.createdAt = now;
        rt.expiresAt = now.plus(refreshTokenDuration);
        rt.persist();
        return plain;
    }

    // ----- helpers -----

    private static String generateToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
