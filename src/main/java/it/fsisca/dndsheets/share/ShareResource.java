package it.fsisca.dndsheets.share;

import io.quarkus.security.Authenticated;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.share.dto.ShareResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Endpoint auth per la gestione dei token di condivisione di una scheda.
 * Per la VIEW pubblica vedi {@link PublicShareResource}.
 */
@Path("/characters/{id}/shares")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "shares", description = "Condivisione read-only delle schede (lato owner)")
public class ShareResource {

    @Inject JsonWebToken jwt;
    @Inject ShareService service;

    @POST
    public Response create(@PathParam("id") String characterId) {
        var result = service.create(currentUser(), characterId);
        return Response.status(Response.Status.CREATED)
                .entity(ShareResponse.fromWithToken(result.token(), result.plain()))
                .build();
    }

    @GET
    public List<ShareResponse> list(@PathParam("id") String characterId) {
        return service.listForCharacter(currentUser(), characterId).stream()
                .map(ShareResponse::from)
                .toList();
    }

    @DELETE
    @Path("/{shareId}")
    public Response revoke(
            @PathParam("id")      String characterId,
            @PathParam("shareId") String shareId) {
        service.revoke(currentUser(), characterId, shareId);
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
