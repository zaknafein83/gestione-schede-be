package it.fsisca.dndsheets.payment;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Optional;

/**
 * Wrapper sulla SDK Stripe per i pagamenti Premium su canale web.
 *
 * <p>Feature-flagged sull'env: se {@link #secretKey}, {@link #webhookSecret} o
 * {@link #priceId} sono vuoti, {@link #isConfigured()} ritorna {@code false}
 * e gli endpoint rispondono 503 STRIPE_NOT_CONFIGURED. Permette di mergiare
 * il codice senza credenziali popolate.</p>
 *
 * <p>Flow nominale (lifetime, no abbonamenti):</p>
 * <ol>
 *   <li>{@link #createCheckoutSession(User)}: crea sessione mode=PAYMENT con
 *       il priceId del prodotto Premium, customer_email pre-popolata,
 *       metadata.userId per ricondurre il pagamento all'utente.</li>
 *   <li>L'utente paga su pagina hosted Stripe, viene rediretto a success_url.</li>
 *   <li>Stripe consegna webhook {@code checkout.session.completed} (eventualmente
 *       piu' volte): il service deduplica via {@link PaymentEvent#stripeEventId}
 *       e flippa tier=PREMIUM al primo successo.</li>
 *   <li>Su {@code charge.refunded}: opzionale auto-downgrade a FREE.</li>
 * </ol>
 */
@ApplicationScoped
public class StripeService {

    private static final Logger LOG = Logger.getLogger(StripeService.class);

    // Optional<String> per lo stesso motivo di GoogleTokenVerifierImpl: SmallRye
    // Config rifiuta di iniettare String quando l'env var risolve a "".
    @ConfigProperty(name = "app.stripe.secret-key")
    Optional<String> secretKeyConfig;

    @ConfigProperty(name = "app.stripe.webhook-secret")
    Optional<String> webhookSecretConfig;

    @ConfigProperty(name = "app.stripe.price-id")
    Optional<String> priceIdConfig;

    @ConfigProperty(name = "app.stripe.success-url")
    Optional<String> successUrlConfig;

    @ConfigProperty(name = "app.stripe.cancel-url")
    Optional<String> cancelUrlConfig;

    private String secretKey;
    private String webhookSecret;
    private String priceId;
    private String successUrl;
    private String cancelUrl;

    @PostConstruct
    void init() {
        secretKey     = secretKeyConfig.orElse("").trim();
        webhookSecret = webhookSecretConfig.orElse("").trim();
        priceId       = priceIdConfig.orElse("").trim();
        successUrl    = successUrlConfig.orElse("").trim();
        cancelUrl     = cancelUrlConfig.orElse("").trim();
        if (isConfigured()) {
            Stripe.apiKey = secretKey;
            LOG.infof("Stripe configurato (priceId=%s)", priceId);
        } else {
            LOG.info("Stripe NON configurato (env vars vuote) — /me/stripe/* e /stripe/webhook risponderanno 503");
        }
    }

    public boolean isConfigured() {
        return !secretKey.isEmpty()
                && !webhookSecret.isEmpty()
                && !priceId.isEmpty()
                && !successUrl.isEmpty()
                && !cancelUrl.isEmpty();
    }

    /**
     * Crea una Checkout Session Stripe per l'acquisto Premium lifetime
     * dell'utente. Ritorna sessionId + URL hosted page.
     */
    public Session createCheckoutSession(User user) {
        if (!isConfigured()) {
            throw new AppException(jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE,
                    "STRIPE_NOT_CONFIGURED",
                    "Il canale di pagamento Stripe non e' configurato su questo server");
        }
        if (user.isPremium()) {
            // No-op idempotente: l'utente e' gia' Premium, niente da vendergli.
            throw AppException.badRequest("ALREADY_PREMIUM",
                    "Sei gia' Premium — niente da pagare");
        }

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setCustomerEmail(user.email)
                // metadata.userId ci permette di ricondurre la session all'utente
                // quando arriva il webhook (non e' visibile lato cliente).
                .putMetadata("userId", user.id.toHexString())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build())
                .build();

        try {
            Session session = Session.create(params);
            LOG.infof("Stripe checkout session creata per %s: %s", user.email, session.getId());
            return session;
        } catch (StripeException e) {
            LOG.errorf(e, "Stripe createCheckoutSession fallita per %s", user.email);
            throw new AppException(jakarta.ws.rs.core.Response.Status.BAD_GATEWAY,
                    "STRIPE_API_ERROR",
                    "Errore durante la creazione della sessione di pagamento");
        }
    }

    /**
     * Verifica firma + dedup + processa l'evento Stripe ricevuto dal webhook.
     * Non lancia eccezioni "applicative" su eventi sconosciuti: vengono solo
     * loggati e ignorati (200 OK al chiamante, Stripe non ritenta).
     *
     * @return true se l'evento ha causato un cambio di stato (utile per i log)
     */
    public boolean handleWebhook(String payload, String signatureHeader) {
        if (!isConfigured()) {
            throw new AppException(jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE,
                    "STRIPE_NOT_CONFIGURED",
                    "Webhook Stripe non configurato su questo server");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            LOG.warnf("Webhook Stripe: firma non valida (%s)", e.getMessage());
            throw AppException.badRequest("INVALID_SIGNATURE", "Firma webhook non valida");
        }

        // Idempotency: se l'event.id e' gia' in DB, no-op.
        if (PaymentEvent.find("stripeEventId", event.getId()).count() > 0) {
            LOG.infof("Webhook %s gia' processato (event.id=%s), skip", event.getType(), event.getId());
            return false;
        }

        return switch (event.getType()) {
            case "checkout.session.completed" -> handleCheckoutCompleted(event);
            case "charge.refunded"            -> handleChargeRefunded(event);
            default -> {
                LOG.infof("Webhook tipo ignorato: %s (event.id=%s)", event.getType(), event.getId());
                yield false;
            }
        };
    }

    private boolean handleCheckoutCompleted(Event event) {
        Optional<Session> sessionOpt = deserialize(event, Session.class);
        if (sessionOpt.isEmpty()) {
            recordEvent(event, null, null, "NO_OP_DESERIALIZE_FAILED");
            return false;
        }
        Session session = sessionOpt.get();

        // Status check: vogliamo solo session "paid", non "unpaid"/"no_payment_required"
        if (!"paid".equals(session.getPaymentStatus())) {
            LOG.infof("checkout.session.completed con paymentStatus=%s, ignoro", session.getPaymentStatus());
            recordEvent(event, session, null, "NO_OP_NOT_PAID");
            return false;
        }

        String userIdHex = session.getMetadata() != null
                ? session.getMetadata().get("userId") : null;
        if (userIdHex == null) {
            LOG.warnf("checkout.session.completed senza metadata.userId (session=%s)", session.getId());
            recordEvent(event, session, null, "NO_OP_NO_USER_METADATA");
            return false;
        }

        User user;
        try {
            user = User.<User>findById(new org.bson.types.ObjectId(userIdHex));
        } catch (IllegalArgumentException ex) {
            LOG.warnf("metadata.userId non e' un ObjectId valido: %s", userIdHex);
            recordEvent(event, session, null, "NO_OP_INVALID_USER_ID");
            return false;
        }
        if (user == null) {
            LOG.warnf("Utente %s non trovato (session=%s)", userIdHex, session.getId());
            recordEvent(event, session, null, "NO_OP_USER_NOT_FOUND");
            return false;
        }

        boolean granted = false;
        if (!user.isPremium()) {
            user.tier          = User.TIER_PREMIUM;
            user.premiumSince  = Instant.now();
            user.premiumSource = User.SRC_STRIPE;
            user.updatedAt     = Instant.now();
            user.update();
            granted = true;
            LOG.infof("Premium concesso a %s via Stripe (session=%s)", user.email, session.getId());
        } else {
            LOG.infof("Utente %s era gia' Premium (idempotency post-init)", user.email);
        }

        recordEvent(event, session, user, granted ? "GRANT_PREMIUM" : "NO_OP_ALREADY_PREMIUM");
        return granted;
    }

    /**
     * Su charge.refunded facciamo auto-downgrade a FREE solo se il pagamento
     * era stato fatto via Stripe (premiumSource=STRIPE). Le schede oltre il
     * limite restano accessibili (no cancellazione automatica).
     */
    private boolean handleChargeRefunded(Event event) {
        // L'event payload e' un Charge; non c'e' direttamente l'userId.
        // Strategia: cerchiamo il PaymentEvent precedente con stesso customer
        // o session id (best-effort). Fallback log e NO_OP.
        Optional<com.stripe.model.Charge> chargeOpt = deserialize(event, com.stripe.model.Charge.class);
        if (chargeOpt.isEmpty()) {
            recordEvent(event, null, null, "NO_OP_DESERIALIZE_FAILED");
            return false;
        }
        com.stripe.model.Charge charge = chargeOpt.get();
        String customerId = charge.getCustomer();

        if (customerId == null) {
            recordEvent(event, null, null, "NO_OP_NO_CUSTOMER");
            return false;
        }

        Optional<PaymentEvent> prev = PaymentEvent
                .<PaymentEvent>find("stripeCustomerId = ?1 and outcome = ?2", customerId, "GRANT_PREMIUM")
                .firstResultOptional();
        if (prev.isEmpty() || prev.get().userId == null) {
            recordEvent(event, null, null, "NO_OP_NO_PRIOR_GRANT");
            return false;
        }

        User user = User.<User>findById(prev.get().userId);
        if (user == null) {
            recordEvent(event, null, null, "NO_OP_USER_NOT_FOUND");
            return false;
        }

        boolean downgraded = false;
        if (user.isPremium() && User.SRC_STRIPE.equals(user.premiumSource)) {
            user.tier          = User.TIER_FREE;
            user.premiumSource = null;
            user.premiumSince  = null;
            user.updatedAt     = Instant.now();
            user.update();
            downgraded = true;
            LOG.infof("Downgrade a FREE per %s su charge.refunded (customer=%s)", user.email, customerId);
        }

        // Salva l'evento con userId per audit (custom builder per evitare di
        // tornare a recordEvent che assume Session)
        PaymentEvent pe = new PaymentEvent();
        pe.stripeEventId   = event.getId();
        pe.eventType       = event.getType();
        pe.stripeCustomerId = customerId;
        pe.userId          = user.id;
        pe.email           = user.email;
        pe.amountTotal     = charge.getAmountRefunded();
        pe.currency        = charge.getCurrency();
        pe.eventCreatedAt  = Instant.ofEpochSecond(event.getCreated());
        pe.processedAt     = Instant.now();
        pe.outcome         = downgraded ? "DOWNGRADE_FREE" : "NO_OP_NOT_STRIPE_PREMIUM";
        pe.persist();
        return downgraded;
    }

    /**
     * Salva un PaymentEvent con i metadati derivati dall'eventuale Session
     * collegata. Se {@code user} e' null, lo userId resta a null.
     */
    private void recordEvent(Event event, Session session, User user, String outcome) {
        PaymentEvent pe = new PaymentEvent();
        pe.stripeEventId = event.getId();
        pe.eventType     = event.getType();
        if (session != null) {
            pe.stripeSessionId  = session.getId();
            pe.stripeCustomerId = session.getCustomer();
            pe.amountTotal      = session.getAmountTotal();
            pe.currency         = session.getCurrency();
        }
        if (user != null) {
            pe.userId = user.id;
            pe.email  = user.email;
        }
        pe.eventCreatedAt = Instant.ofEpochSecond(event.getCreated());
        pe.processedAt    = Instant.now();
        pe.outcome        = outcome;
        pe.persist();
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<T> deserialize(Event event, Class<T> type) {
        EventDataObjectDeserializer d = event.getDataObjectDeserializer();
        return d.getObject().filter(type::isInstance).map(o -> (T) o);
    }
}
