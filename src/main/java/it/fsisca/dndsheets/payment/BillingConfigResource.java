package it.fsisca.dndsheets.payment;

import it.fsisca.dndsheets.payment.dto.PaddleClientConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Config pubblica del pagamento, consumata dalla pagina statica
 * {@code checkout.html} (token client Paddle + priceId + environment) e dal
 * frontend per sapere se il checkout e' abilitato. Risponde sempre 200: quando
 * non configurato ritorna {@code enabled=false} (degradazione "presto disponibile").
 */
@Path("/billing/config")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "payment", description = "Config pubblica pagamento Paddle")
public class BillingConfigResource {

    @Inject PaddleService paddleService;

    @GET
    public PaddleClientConfig config() {
        return paddleService.clientConfig();
    }
}
