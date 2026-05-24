package it.fsisca.dndsheets.admin;

import it.fsisca.dndsheets.admin.dto.AdminUserPage;
import it.fsisca.dndsheets.admin.dto.AdminUserSummary;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Endpoint admin. Richiede JWT con group "admin".
 * Convenzione path: /admin/* — non sovrapponibile alle altre risorse.
 */
@Path("/admin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("admin")
@Tag(name = "admin", description = "Gestione utenti — solo per admin")
public class AdminResource {

    @Inject JsonWebToken jwt;
    @Inject AdminService adminService;

    @GET
    @Path("/users")
    public AdminUserPage listUsers(
            @QueryParam("q") String q,
            @QueryParam("page")     @DefaultValue("0")  int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        // Forza l'accesso del chiamante (anche se RolesAllowed gia' garantisce)
        currentAdmin();
        return adminService.listUsers(q, page, pageSize);
    }

    @POST
    @Path("/users/{id}/grant-premium")
    @Consumes(MediaType.WILDCARD)
    public AdminUserSummary grantPremium(@PathParam("id") String id) {
        return adminService.grantPremium(currentAdmin(), id);
    }

    @POST
    @Path("/users/{id}/revoke-premium")
    @Consumes(MediaType.WILDCARD)
    public AdminUserSummary revokePremium(@PathParam("id") String id) {
        return adminService.revokePremium(currentAdmin(), id);
    }

    @DELETE
    @Path("/users/{id}")
    public Response deleteUser(@PathParam("id") String id) {
        adminService.deleteUser(currentAdmin(), id);
        return Response.noContent().build();
    }

    // ----- helpers -----

    private User currentAdmin() {
        String sub = jwt.getSubject();
        if (sub == null) {
            throw AppException.unauthorized("INVALID_TOKEN", "Token senza subject");
        }
        return User.<User>findByIdOptional(new ObjectId(sub))
                .orElseThrow(() -> AppException.unauthorized("USER_NOT_FOUND",
                        "Utente del token non trovato"));
    }
}
