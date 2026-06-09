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
 * Endpoint pubblico ricevente i webhook di Paddle. Autenticato esclusivamente
 * via firma HMAC (header {@code Paddle-Signature} + secret della destination
 * configurato nel server). Nessun JWT richiesto.
 *
 * <p>Paddle puo' consegnare lo stesso evento piu' volte (retry su risposta
 * non-2xx): {@link PaddleService#handleWebhook} fa dedup via {@code event_id}.</p>
 *
 * <p>URL esterno (dietro nginx): {@code https://pg.zaknafein.ovh/api/billing/webhook}.</p>
 */
@Path("/billing/webhook")
@Tag(name = "payment", description = "Webhook Paddle (server-to-server)")
public class BillingWebhookResource {

    private static final Logger LOG = Logger.getLogger(BillingWebhookResource.class);

    @Inject PaddleService paddleService;

    /**
     * Riceve il payload raw dell'evento Paddle. NON usiamo @Consumes JSON che
     * triggererebbe normalizzazione: serve il body byte-exact per la firma HMAC.
     */
    @POST
    @Consumes(MediaType.WILDCARD)
    public Response receive(@HeaderParam("Paddle-Signature") String signature,
                            String payload) {
        if (signature == null || signature.isBlank()) {
            LOG.warn("Webhook ricevuto senza header Paddle-Signature");
            return Response.status(400).build();
        }
        if (payload == null || payload.isBlank()) {
            return Response.status(400).build();
        }
        // Eventuali AppException (503 non configurato, 401 firma non valida,
        // 400 payload) salgono al RestExceptionMapper. Sugli eventi no-op
        // rispondiamo 200 cosi' Paddle non ritenta.
        paddleService.handleWebhook(payload, signature);
        return Response.ok().build();
    }
}
