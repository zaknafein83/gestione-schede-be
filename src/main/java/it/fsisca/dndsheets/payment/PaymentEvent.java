package it.fsisca.dndsheets.payment;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Audit + idempotency record per gli eventi webhook ricevuti dal processore
 * di pagamento (Paddle, Merchant of Record sul canale web).
 *
 * <p>{@code eventId} e' la chiave di idempotency: Paddle puo' consegnare lo
 * stesso evento piu' volte (retry su risposta non-2xx). L'indice univoco su
 * questa colonna (vedi {@link it.fsisca.dndsheets.common.MongoIndexes}) fa sì
 * che il service deduplichi prima di riprocessare.</p>
 */
@MongoEntity(collection = "payment_events")
public class PaymentEvent extends PanacheMongoEntity {

    /** {@code event_id} dal payload Paddle (es. "evt_01h..."). Univoco. */
    public String eventId;

    /** Tipo evento (es. "transaction.completed", "adjustment.created"). */
    public String eventType;

    /** Transazione Paddle collegata (txn_...); null se l'evento non ne ha una. */
    public String transactionId;

    /** Customer Paddle (ctm_...); null se assente nel payload. */
    public String paddleCustomerId;

    /** ObjectId del nostro {@link it.fsisca.dndsheets.auth.User}. Null se non risolto. */
    public ObjectId userId;

    /** Email dello user al momento dell'evento (snapshot). */
    public String email;

    /** Totale in minor units come stringa (Paddle restituisce stringhe, es. "499"). */
    public String amount;

    /** Codice valuta ISO (es. "EUR"). */
    public String currency;

    /** {@code occurred_at} di Paddle. */
    public Instant eventCreatedAt;

    /** Quando l'abbiamo processato lato server. */
    public Instant processedAt;

    /** Azione intrapresa: "GRANT_PREMIUM", "DOWNGRADE_FREE", "NO_OP_*". */
    public String outcome;
}
