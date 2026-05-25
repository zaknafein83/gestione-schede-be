package it.fsisca.dndsheets.profile;

import io.quarkus.security.Authenticated;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.auth.dto.UserResponse;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.profile.dto.ChangePasswordRequest;
import it.fsisca.dndsheets.profile.dto.DeleteAccountRequest;
import it.fsisca.dndsheets.profile.dto.UpdateMeRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/**
 * Endpoint del profilo utente corrente. Protetti: richiedono JWT valido.
 */
@Path("/me")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "profile", description = "Profilo dell'utente autenticato")
public class MeResource {

    @Inject JsonWebToken   jwt;
    @Inject ProfileService profileService;
    @Inject AvatarService  avatarService;
    @Inject ExportService  exportService;

    @GET
    public UserResponse get() {
        return UserResponse.from(currentUser());
    }

    @PATCH
    public UserResponse update(@Valid UpdateMeRequest req) {
        return UserResponse.from(profileService.updateMe(currentUser(), req));
    }

    @POST
    @Path("/change-password")
    public Response changePassword(@Valid ChangePasswordRequest req) {
        profileService.changePassword(currentUser(), req);
        return Response.noContent().build();
    }

    /**
     * Cancellazione account — hard delete cascade. Irreversibile.
     * Usiamo POST anziché DELETE per supportare il body con password senza
     * problemi di proxy intermedi che strippano body sui DELETE.
     */
    @POST
    @Path("/delete-account")
    public Response deleteAccount(@Valid DeleteAccountRequest req) {
        profileService.deleteAccount(currentUser(), req.password());
        return Response.noContent().build();
    }

    /**
     * Export dei dati personali (GDPR art. 15 + art. 20). Restituisce un
     * JSON scaricabile con account, schede, dice rolls e share tokens.
     * NON include avatar/portrait binari (scaricabili separatamente) né
     * password hash o token di sessione.
     */
    @GET
    @Path("/export")
    public Response exportMyData() {
        User user = currentUser();
        var payload = exportService.buildExport(user);
        String filename = "pg5e-export-" + user.id.toHexString() + "-"
                + java.time.Instant.now().getEpochSecond() + ".json";
        return Response.ok(payload)
                .type(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    // ---------------- Avatar ----------------

    @POST
    @Path("/avatar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public UserResponse uploadAvatar(@RestForm("file") FileUpload file) {
        if (file == null) {
            throw AppException.badRequest("MISSING_FILE", "Manca il campo 'file'");
        }
        User user = currentUser();
        avatarService.upload(user, file.uploadedFile(), file.contentType(), file.size(), file.fileName());
        return UserResponse.from(user);
    }

    @GET
    @Path("/avatar")
    @Produces(MediaType.WILDCARD)
    public Response downloadAvatar() {
        var dl = avatarService.download(currentUser());
        StreamingOutput out = output -> {
            try (var in = dl.stream()) {
                in.transferTo(output);
            }
        };
        return Response.ok(out)
                .type(dl.contentType())
                .header("Content-Length", dl.length())
                .build();
    }

    @DELETE
    @Path("/avatar")
    public Response deleteAvatar() {
        avatarService.delete(currentUser());
        return Response.noContent().build();
    }

    // ----- helpers -----

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
