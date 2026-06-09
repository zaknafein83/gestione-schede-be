package it.fsisca.dndsheets.payment;

import io.quarkus.security.Authenticated;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.payment.dto.CheckoutResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Endpoint autenticati per il flusso di pagamento Premium lato utente.
 * Il webhook (pubblico) e' in {@link BillingWebhookResource}.
 */
@Path("/me/billing")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "payment", description = "Pagamenti Premium via Paddle")
public class MeBillingResource {

    @Inject JsonWebToken  jwt;
    @Inject PaddleService paddleService;

    /**
     * Costruisce l'URL della pagina di checkout per l'utente corrente e lo
     * ritorna. L'id utente viene messo server-side dal JWT (non spoofabile).
     */
    @POST
    @Path("/checkout")
    public CheckoutResponse createCheckout() {
        User user = currentUser();
        return new CheckoutResponse(paddleService.buildCheckoutUrl(user));
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
