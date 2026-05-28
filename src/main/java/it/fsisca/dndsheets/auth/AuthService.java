package it.fsisca.dndsheets.auth;

import it.fsisca.dndsheets.auth.dto.GoogleLoginRequest;
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
import java.util.Optional;

/**
 * Logica applicativa della feature auth (F1): registrazione e verifica email.
 */
@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 32;   // 32 byte -> 43 char base64url

    @Inject PasswordHasher       passwordHasher;
    @Inject MailService          mailService;
    @Inject JwtService           jwtService;
    @Inject GoogleTokenVerifier  googleTokenVerifier;

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
        user.email              = email;
        user.passwordHash       = passwordHasher.hash(req.password());
        user.emailVerified      = false;
        user.username           = username;
        user.displayName        = req.displayName().trim();
        user.createdAt          = now;
        user.updatedAt          = now;
        // Proof of consent GDPR (art. 7(1)): la @AssertTrue su acceptPrivacy
        // garantisce che siamo qui solo se l'utente ha spuntato il flag.
        user.privacyAcceptedAt  = now;
        // Autocertificazione eta' minima (art. 8 GDPR + art. 2-quinquies
        // D.Lgs 101/2018): @AssertTrue su declareMinAge ci garantisce idem.
        user.ageDeclaredAt      = now;
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

    /**
     * Login/registrazione via Google ID token (OIDC).
     *
     * <p>Strategia di matching:</p>
     * <ol>
     *   <li><b>Match su googleSub</b>: utente che si e' gia' loggato con
     *       Google in passato. Login diretto.</li>
     *   <li><b>Match su email</b>: utente esistente registrato via
     *       email+password. Auto-link silenzioso: salviamo {@code googleSub}
     *       sull'utente e logghiamo. Sicuro perche' Google ha verificato l'email
     *       (campo {@code email_verified=true} nel token), quindi non c'e'
     *       rischio di account takeover. Se l'utente non aveva ancora
     *       verificato la sua email, la marchiamo verificata adesso.</li>
     *   <li><b>Nessun match</b>: registrazione di un nuovo utente. Richiede
     *       {@code acceptPrivacy=true} (proof of consent GDPR). Username
     *       auto-derivato dall'email con suffisso {@code _2,_3,...} su
     *       collisione. {@code emailVerified=true} immediato (skip flusso
     *       verifica email).</li>
     * </ol>
     */
    public LoginResponse googleLogin(GoogleLoginRequest req) {
        GoogleTokenVerifier.GoogleIdentity identity;
        try {
            identity = googleTokenVerifier.verify(req.idToken());
        } catch (GoogleTokenVerifier.InvalidGoogleTokenException e) {
            if ("NOT_CONFIGURED".equals(e.getMessage())) {
                throw new AppException(jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE,
                        "GOOGLE_LOGIN_NOT_CONFIGURED",
                        "Il login con Google non e' configurato su questo server");
            }
            LOG.infof("googleLogin: token rifiutato (%s)", e.getMessage());
            throw AppException.unauthorized("INVALID_GOOGLE_TOKEN",
                    "ID token Google non valido");
        }

        if (!identity.emailVerified()) {
            // Account Google senza email verificata: rarissimo (workspace
            // particolari) ma teoricamente possibile. Rifiutiamo per non
            // assegnare account su email non controllata.
            throw AppException.unauthorized("GOOGLE_EMAIL_NOT_VERIFIED",
                    "L'email Google non risulta verificata");
        }

        String email = identity.email().toLowerCase();
        Instant now = Instant.now();

        // 1) Match per googleSub
        Optional<User> bySub = User.findByGoogleSub(identity.sub());
        if (bySub.isPresent()) {
            return issueLoginResponse(bySub.get());
        }

        // 2) Auto-link per email
        Optional<User> byEmail = User.findByEmail(email);
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            user.googleSub = identity.sub();
            if (!user.emailVerified) {
                user.emailVerified = true;
            }
            user.updatedAt = now;
            user.update();
            LOG.infof("googleLogin: auto-link Google a utente esistente %s", user.email);
            return issueLoginResponse(user);
        }

        // 3) Nuova registrazione via Google. Acccept privacy + dichiarazione
        // eta' minima obbligatori (stessa semantica di POST /auth/register,
        // ma validati nel service perche' i campi non sono @AssertTrue).
        if (!req.acceptPrivacy()) {
            throw AppException.badRequest("PRIVACY_NOT_ACCEPTED",
                    "Devi accettare la Privacy Policy e i Termini di Servizio per registrarti");
        }
        if (!req.declareMinAge()) {
            throw AppException.badRequest("AGE_NOT_DECLARED",
                    "Devi dichiarare di avere almeno 14 anni per registrarti");
        }

        User user = new User();
        user.email             = email;
        user.passwordHash      = null;  // social-only
        user.emailVerified     = true;  // Google ha gia' verificato
        user.username          = deriveUniqueUsername(email);
        user.displayName       = chooseDisplayName(identity, email);
        user.googleSub         = identity.sub();
        user.createdAt         = now;
        user.updatedAt         = now;
        user.privacyAcceptedAt = now;
        user.ageDeclaredAt     = now;
        user.applyDefaults();
        user.persist();
        LOG.infof("googleLogin: nuovo utente registrato via Google %s (username=%s)",
                user.email, user.username);
        return issueLoginResponse(user);
    }

    /** Emette access+refresh token per {@code user} e ritorna il payload login. */
    private LoginResponse issueLoginResponse(User user) {
        String accessToken  = jwtService.issueAccessToken(user);
        String refreshPlain = issueRefreshToken(user);
        return new LoginResponse(accessToken, refreshPlain, UserResponse.from(user));
    }

    /**
     * Deriva uno username valido dall'email aggiungendo suffissi numerici se
     * collide con uno esistente. Il backend impone {@code [a-zA-Z0-9_]{3,30}},
     * quindi sanitizziamo i caratteri non ammessi (es. punti → underscore).
     */
    String deriveUniqueUsername(String email) {
        String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        String base = local.replaceAll("[^a-zA-Z0-9_]", "_");
        if (base.length() < 3) base = base + "_user";
        if (base.length() > 27) base = base.substring(0, 27);

        if (!User.existsByUsername(base)) return base;
        for (int i = 2; i < 1000; i++) {
            String suffix = "_" + i;
            String candidate = (base.length() + suffix.length() > 30)
                    ? base.substring(0, 30 - suffix.length()) + suffix
                    : base + suffix;
            if (!User.existsByUsername(candidate)) return candidate;
        }
        // Bound paranoico: 998 collisioni sulla stessa base sono praticamente
        // impossibili; se accade qualcosa di strano fallback a base + epoch.
        return (base.length() > 18 ? base.substring(0, 18) : base) + "_" + now().toEpochMilli();
    }

    /** Hook estraibile per i test. */
    Instant now() {
        return Instant.now();
    }

    private static String chooseDisplayName(GoogleTokenVerifier.GoogleIdentity id, String emailFallback) {
        if (id.name() != null && !id.name().isBlank()) return id.name().trim();
        // Fallback all'local-part dell'email se Google non ha fornito name.
        int at = emailFallback.indexOf('@');
        return at > 0 ? emailFallback.substring(0, at) : emailFallback;
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
