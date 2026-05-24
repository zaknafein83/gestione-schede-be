package it.fsisca.dndsheets.dice;

import io.quarkus.security.Authenticated;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.dice.dto.DiceRollPayload;
import it.fsisca.dndsheets.dice.dto.DiceRollResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Cronologia tiri dadi dell'utente loggato.
 */
@Path("/dice-rolls")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "dice-rolls", description = "Cronologia tiri dadi")
public class DiceRollResource {

    @Inject JsonWebToken    jwt;
    @Inject DiceRollService service;

    @POST
    public Response create(@Valid DiceRollPayload payload) {
        var r = service.create(currentUser(), payload);
        return Response.status(Response.Status.CREATED)
                .entity(DiceRollResponse.from(r))
                .build();
    }

    @GET
    public List<DiceRollResponse> list(
            @QueryParam("characterId") String characterId,
            @QueryParam("limit")  @DefaultValue("50") Integer limit) {
        return service.listForOwner(currentUser(), characterId, limit).stream()
                .map(DiceRollResponse::from)
                .toList();
    }

    @DELETE
    public Response clear() {
        service.deleteAllForOwner(currentUser());
        return Response.noContent().build();
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
