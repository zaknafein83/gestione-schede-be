package it.fsisca.dndsheets.character;

import io.quarkus.security.Authenticated;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.character.dto.CharacterLayoutPayload;
import it.fsisca.dndsheets.character.dto.CharacterLayoutResponse;
import it.fsisca.dndsheets.common.AppException;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Endpoint per il layout dashboard personalizzato di una scheda.
 * GET libero per tutti gli utenti autenticati; PUT richiede Premium.
 */
@Path("/characters/{id}/layout")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "character-layout", description = "Layout dashboard personalizzato")
public class CharacterLayoutResource {

    @Inject JsonWebToken            jwt;
    @Inject CharacterLayoutService  layoutService;

    @GET
    public CharacterLayoutResponse get(@PathParam("id") String characterId) {
        return layoutService.findFor(currentUser(), characterId)
                .map(CharacterLayoutResponse::from)
                .orElseGet(CharacterLayoutResponse::defaultLayout);
    }

    @PUT
    public CharacterLayoutResponse save(@PathParam("id") String characterId,
                                        @Valid CharacterLayoutPayload payload) {
        var saved = layoutService.save(currentUser(), characterId, payload);
        return CharacterLayoutResponse.from(saved);
    }

    @DELETE
    public Response delete(@PathParam("id") String characterId) {
        layoutService.reset(currentUser(), characterId);
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
