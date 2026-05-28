package it.fsisca.dndsheets.payment;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Audit + idempotency record per eventi Stripe processati dal webhook.
 *
 * <p>{@code stripeEventId} e' la chiave di idempotency: Stripe puo' consegnare
 * lo stesso evento piu' volte (best-effort delivery) e il backend deve essere
 * resiliente. L'indice univoco su questa colonna garantisce che la seconda
 * insert lanci DuplicateKey, intercettata dal service.</p>
 */
@MongoEntity(collection = "payment_events")
public class PaymentEvent extends PanacheMongoEntity {

    /** event.id dal payload Stripe (es. "evt_1NA..."). Univoco. */
    public String stripeEventId;

    /** Tipo evento (es. "checkout.session.completed", "charge.refunded"). */
    public String eventType;

    /** Checkout session id (per i checkout.session.*); null per altri eventi. */
    public String stripeSessionId;

    /** Customer Stripe (cus_...); null se l'evento e' anonimo. */
    public String stripeCustomerId;

    /** ObjectId del nostro {@link it.fsisca.dndsheets.auth.User} (hex). */
    public ObjectId userId;

    /** Email dello user al momento dell'evento (snapshot). */
    public String email;

    /** Totale pagato in cents della valuta. */
    public Long amountTotal;

    /** Codice valuta ISO (es. "eur"). */
    public String currency;

    /** {@code event.created} di Stripe (UTC). */
    public Instant eventCreatedAt;

    /** Quando l'abbiamo processato lato server. */
    public Instant processedAt;

    /** Quale azione abbiamo intrapreso (es. "GRANT_PREMIUM", "DOWNGRADE_FREE", "NO_OP"). */
    public String outcome;
}
