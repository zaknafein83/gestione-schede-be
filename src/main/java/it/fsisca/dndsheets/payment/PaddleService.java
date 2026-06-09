package it.fsisca.dndsheets.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.payment.dto.PaddleClientConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

/**
 * Servizio per i pagamenti Premium via Paddle (Merchant of Record sul web).
 *
 * <p>Feature-flagged sull'ambiente: se {@code client-token}, {@code price-id}
 * o {@code webhook-secret} sono vuoti, {@link #isConfigured()} ritorna
 * {@code false} e gli endpoint protetti rispondono 503 PADDLE_NOT_CONFIGURED.
 * Permette di mergiare il codice senza credenziali popolate.</p>
 *
 * <p>Flow nominale (lifetime one-time, no abbonamenti):</p>
 * <ol>
 *   <li>{@link #buildCheckoutUrl(User)}: costruisce l'URL della pagina statica
 *       {@code /checkout.html} con {@code uid} (id utente, server-side, non
 *       spoofabile) ed {@code email} pre-popolata. La pagina apre l'overlay
 *       Paddle.js col {@code price-id} configurato e {@code custom_data.user_id}.</li>
 *   <li>L'utente paga sull'overlay Paddle (Paddle = Merchant of Record).</li>
 *   <li>Paddle consegna il webhook {@code transaction.completed}: il service
 *       verifica la firma HMAC {@code Paddle-Signature}, deduplica via
 *       {@link PaymentEvent#eventId} e flippa tier=PREMIUM al primo successo.</li>
 *   <li>Su {@code adjustment.created} (action=refund/chargeback): auto-downgrade
 *       a FREE se il premium proveniva da Paddle.</li>
 * </ol>
 */
@ApplicationScoped
public class PaddleService {

    private static final Logger LOG = Logger.getLogger(PaddleService.class);

    // Optional<String>: SmallRye Config rifiuta di iniettare String quando
    // l'env var risolve a "" (stesso pattern di GoogleTokenVerifier / ex-Stripe).
    @ConfigProperty(name = "app.paddle.client-token")
    Optional<String> clientTokenConfig;

    @ConfigProperty(name = "app.paddle.price-id")
    Optional<String> priceIdConfig;

    @ConfigProperty(name = "app.paddle.webhook-secret")
    Optional<String> webhookSecretConfig;

    @ConfigProperty(name = "app.paddle.environment")
    Optional<String> environmentConfig;

    /** Finestra di tolleranza (secondi) sul timestamp della firma, anti-replay. */
    @ConfigProperty(name = "app.paddle.webhook-max-age-seconds", defaultValue = "300")
    long webhookMaxAgeSeconds;

    /** Base URL del frontend: usata per costruire l'URL di checkout.html. */
    @ConfigProperty(name = "app.frontend.base-url")
    String frontendBaseUrl;

    @Inject ObjectMapper mapper;

    private String clientToken;
    private String priceId;
    private String webhookSecret;
    private String environment;

    @PostConstruct
    void init() {
        clientToken   = clientTokenConfig.orElse("").trim();
        priceId       = priceIdConfig.orElse("").trim();
        webhookSecret = webhookSecretConfig.orElse("").trim();
        environment   = environmentConfig.orElse("sandbox").trim();
        if (isConfigured()) {
            LOG.infof("Paddle configurato (env=%s, priceId=%s)", environment, priceId);
        } else {
            LOG.info("Paddle NON configurato (env vars vuote) — /me/billing/* e /billing/webhook risponderanno 503");
        }
    }

    public boolean isConfigured() {
        return !clientToken.isEmpty() && !priceId.isEmpty() && !webhookSecret.isEmpty();
    }

    /** Config pubblica consumata da checkout.html (token client + priceId + env). */
    public PaddleClientConfig clientConfig() {
        boolean on = isConfigured();
        return new PaddleClientConfig(
                on,
                environment,
                on ? clientToken : null,
                on ? priceId : null);
    }

    /**
     * Costruisce l'URL della pagina di checkout per l'utente. L'id utente viene
     * messo server-side (dal JWT a monte): il client non puo' falsificarlo.
     */
    public String buildCheckoutUrl(User user) {
        if (!isConfigured()) {
            throw new AppException(Response.Status.SERVICE_UNAVAILABLE,
                    "PADDLE_NOT_CONFIGURED",
                    "Il canale di pagamento non e' configurato su questo server");
        }
        if (user.isPremium()) {
            throw AppException.badRequest("ALREADY_PREMIUM", "Sei gia' Premium — niente da pagare");
        }
        String email = URLEncoder.encode(user.email, StandardCharsets.UTF_8);
        String base = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        return base + "/checkout.html?uid=" + user.id.toHexString() + "&email=" + email;
    }

    // =====================================================================
    //                              Webhook
    // =====================================================================

    /**
     * Verifica firma + dedup + processa l'evento Paddle. Non lancia eccezioni
     * "applicative" su eventi sconosciuti: vengono loggati e ignorati (il
     * chiamante risponde 200 cosi' Paddle non ritenta).
     *
     * @return true se l'evento ha causato un cambio di stato (utile per i log)
     */
    public boolean handleWebhook(String rawBody, String signatureHeader) {
        if (!isConfigured()) {
            throw new AppException(Response.Status.SERVICE_UNAVAILABLE,
                    "PADDLE_NOT_CONFIGURED",
                    "Webhook non configurato su questo server");
        }
        if (!verifySignature(rawBody, signatureHeader)) {
            throw new AppException(Response.Status.UNAUTHORIZED,
                    "INVALID_SIGNATURE", "Firma webhook non valida");
        }

        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw AppException.badRequest("INVALID_PAYLOAD", "Payload webhook non valido");
        }

        String eventId   = text(root, "event_id");
        String eventType = text(root, "event_type");
        if (eventId == null || eventType == null) {
            throw AppException.badRequest("INVALID_PAYLOAD", "Payload webhook senza event_id/event_type");
        }

        // Idempotency: se l'event_id e' gia' in DB, no-op.
        if (PaymentEvent.find("eventId", eventId).count() > 0) {
            LOG.infof("Webhook %s gia' processato (event_id=%s), skip", eventType, eventId);
            return false;
        }

        Instant occurredAt = parseInstant(text(root, "occurred_at"));
        JsonNode data = root.get("data");

        return switch (eventType) {
            case "transaction.completed", "transaction.paid" ->
                    handleTransactionCompleted(eventId, eventType, occurredAt, data);
            case "adjustment.created" ->
                    handleAdjustmentCreated(eventId, eventType, occurredAt, data);
            default -> {
                LOG.infof("Webhook tipo ignorato: %s (event_id=%s)", eventType, eventId);
                recordEvent(eventId, eventType, occurredAt, data, null, "NO_OP_IGNORED_TYPE");
                yield false;
            }
        };
    }

    private boolean handleTransactionCompleted(String eventId, String eventType,
                                               Instant occurredAt, JsonNode data) {
        if (data == null) {
            recordEvent(eventId, eventType, occurredAt, null, null, "NO_OP_NO_DATA");
            return false;
        }
        String userIdHex = data.path("custom_data").path("user_id").asText(null);
        if (userIdHex == null || userIdHex.isBlank()) {
            LOG.warnf("transaction.completed senza custom_data.user_id (txn=%s)", text(data, "id"));
            recordEvent(eventId, eventType, occurredAt, data, null, "NO_OP_NO_USER_METADATA");
            return false;
        }

        User user;
        try {
            user = User.findById(new ObjectId(userIdHex));
        } catch (IllegalArgumentException ex) {
            LOG.warnf("custom_data.user_id non e' un ObjectId valido: %s", userIdHex);
            recordEvent(eventId, eventType, occurredAt, data, null, "NO_OP_INVALID_USER_ID");
            return false;
        }
        if (user == null) {
            LOG.warnf("Utente %s non trovato (txn=%s)", userIdHex, text(data, "id"));
            recordEvent(eventId, eventType, occurredAt, data, null, "NO_OP_USER_NOT_FOUND");
            return false;
        }

        boolean granted = false;
        if (!user.isPremium()) {
            user.tier          = User.TIER_PREMIUM;
            user.premiumSince  = Instant.now();
            user.premiumSource = User.SRC_PADDLE;
            user.updatedAt     = Instant.now();
            user.update();
            granted = true;
            LOG.infof("Premium concesso a %s via Paddle (txn=%s)", user.email, text(data, "id"));
        } else {
            LOG.infof("Utente %s era gia' Premium (idempotency)", user.email);
        }
        recordEvent(eventId, eventType, occurredAt, data, user,
                granted ? "GRANT_PREMIUM" : "NO_OP_ALREADY_PREMIUM");
        return granted;
    }

    /**
     * Su un adjustment di tipo refund/chargeback facciamo auto-downgrade a FREE
     * se il premium era stato concesso via Paddle. Attribuzione: cerchiamo il
     * GRANT precedente sulla stessa transazione. Le schede oltre il limite free
     * NON vengono cancellate.
     */
    private boolean handleAdjustmentCreated(String eventId, String eventType,
                                            Instant occurredAt, JsonNode data) {
        if (data == null) {
            recordEvent(eventId, eventType, occurredAt, null, null, "NO_OP_NO_DATA");
            return false;
        }
        String action = text(data, "action");
        if (!"refund".equals(action) && !"chargeback".equals(action)) {
            recordEvent(eventId, eventType, occurredAt, data, null, "NO_OP_NOT_REFUND");
            return false;
        }
        String txnId = text(data, "transaction_id");
        if (txnId == null) {
            recordEvent(eventId, eventType, occurredAt, data, null, "NO_OP_NO_TRANSACTION");
            return false;
        }

        Optional<PaymentEvent> prev = PaymentEvent
                .<PaymentEvent>find("transactionId = ?1 and outcome = ?2", txnId, "GRANT_PREMIUM")
                .firstResultOptional();
        if (prev.isEmpty() || prev.get().userId == null) {
            recordEvent(eventId, eventType, occurredAt, data, null, "NO_OP_NO_PRIOR_GRANT");
            return false;
        }

        User user = User.findById(prev.get().userId);
        if (user == null) {
            recordEvent(eventId, eventType, occurredAt, data, null, "NO_OP_USER_NOT_FOUND");
            return false;
        }

        boolean downgraded = false;
        if (user.isPremium() && User.SRC_PADDLE.equals(user.premiumSource)) {
            user.tier          = User.TIER_FREE;
            user.premiumSource = null;
            user.premiumSince  = null;
            user.updatedAt     = Instant.now();
            user.update();
            downgraded = true;
            LOG.infof("Downgrade a FREE per %s su %s (txn=%s)", user.email, action, txnId);
        }
        recordEvent(eventId, eventType, occurredAt, data, user,
                downgraded ? "DOWNGRADE_FREE" : "NO_OP_NOT_PADDLE_PREMIUM");
        return downgraded;
    }

    // =====================================================================
    //                              Helpers
    // =====================================================================

    /**
     * Verifica l'header {@code Paddle-Signature} (formato {@code ts=...;h1=...}).
     * La firma e' HMAC-SHA256 di {@code "<ts>:<rawBody>"} con il secret della
     * destination. Il body NON deve essere alterato (serve raw byte-exact).
     */
    boolean verifySignature(String rawBody, String header) {
        if (header == null || header.isBlank()) return false;
        String ts = null, h1 = null;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if (k.equals("ts")) ts = v;
            else if (k.equals("h1")) h1 = v;
        }
        if (ts == null || h1 == null) return false;

        // Anti-replay: rifiuta firme troppo vecchie (clock skew tollerato).
        try {
            long age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(ts));
            if (webhookMaxAgeSeconds > 0 && age > webhookMaxAgeSeconds) {
                LOG.warnf("Webhook Paddle: timestamp fuori tolleranza (age=%ds)", age);
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        String expected = hmacSha256Hex(webhookSecret, ts + ":" + rawBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                h1.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                 .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 non disponibile", e);
        }
    }

    private void recordEvent(String eventId, String eventType, Instant occurredAt,
                             JsonNode data, User user, String outcome) {
        PaymentEvent pe = new PaymentEvent();
        pe.eventId   = eventId;
        pe.eventType = eventType;
        if (data != null) {
            pe.transactionId    = text(data, "id");
            pe.paddleCustomerId = text(data, "customer_id");
            pe.currency         = text(data, "currency_code");
            pe.amount           = data.path("details").path("totals").path("grand_total").asText(null);
            // Per gli adjustment l'id transazione e' in "transaction_id".
            if (pe.transactionId == null) pe.transactionId = text(data, "transaction_id");
        }
        if (user != null) {
            pe.userId = user.id;
            pe.email  = user.email;
        }
        pe.eventCreatedAt = occurredAt;
        pe.processedAt    = Instant.now();
        pe.outcome        = outcome;
        pe.persist();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Instant parseInstant(String iso) {
        if (iso == null) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
