package it.fsisca.dndsheets.payment;

import com.stripe.model.checkout.Session;
import io.quarkus.security.Authenticated;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.payment.dto.CheckoutSessionResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Endpoint autenticati per il flusso di pagamento Stripe lato utente.
 * Il webhook (publico) e' in {@link StripeWebhookResource}.
 */
@Path("/me/stripe")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "payment", description = "Pagamenti Premium via Stripe")
public class MeStripeResource {

    @Inject JsonWebToken  jwt;
    @Inject StripeService stripeService;

    /**
     * Crea una Stripe Checkout Session per l'acquisto Premium e ritorna
     * l'URL hosted page su cui il frontend deve redirigere l'utente.
     */
    @POST
    @Path("/checkout-session")
    public CheckoutSessionResponse createCheckoutSession() {
        User user = currentUser();
        Session session = stripeService.createCheckoutSession(user);
        return new CheckoutSessionResponse(session.getId(), session.getUrl());
    }

    private User currentUser() {
        String sub = jwt.getSubject();
        if (sub == null) {
            throw AppException.unauthorized("INVALID_TOKEN", "Token senza subject");
        }
        return User.<User>findByIdOptional(new ObjectId(sub))
                .orElseThrow(() -> AppException.unauthorized("USER_NOT_FOUND",
                        "Utente del token non trovato"));
    }
}
