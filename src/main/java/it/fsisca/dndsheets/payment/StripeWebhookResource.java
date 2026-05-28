package it.fsisca.dndsheets.payment;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * Endpoint pubblico ricevente i webhook di Stripe. Autenticato esclusivamente
 * via firma HMAC (header {@code Stripe-Signature} + secret configurato nel
 * server). Nessun JWT richiesto, nessuna sessione utente.
 *
 * <p>Stripe puo' consegnare lo stesso evento piu' volte: il
 * {@link StripeService#handleWebhook} fa dedup via {@code event.id}.</p>
 */
@Path("/stripe/webhook")
@Tag(name = "payment", description = "Webhook Stripe (server-to-server)")
public class StripeWebhookResource {

    private static final Logger LOG = Logger.getLogger(StripeWebhookResource.class);

    @Inject StripeService stripeService;

    /**
     * Riceve il payload raw dell'evento Stripe. NON usiamo @Consumes JSON che
     * triggererebbe parsing/normalizzazione: serve il body byte-exact per la
     * verifica firma HMAC.
     */
    @POST
    @Consumes(MediaType.WILDCARD)
    public Response receive(@HeaderParam("Stripe-Signature") String signature,
                            String payload) {
        if (signature == null || signature.isBlank()) {
            LOG.warn("Webhook ricevuto senza header Stripe-Signature");
            return Response.status(400).build();
        }
        if (payload == null || payload.isBlank()) {
            return Response.status(400).build();
        }
        try {
            stripeService.handleWebhook(payload, signature);
            // Anche se l'evento e' "no-op" rispondiamo 200: Stripe non deve ritentare.
            return Response.ok().build();
        } catch (it.fsisca.dndsheets.common.AppException e) {
            // Es. firma non valida (400) o Stripe non configurato (503).
            // Lasciamo passare al RestExceptionMapper.
            throw e;
        }
    }
}
