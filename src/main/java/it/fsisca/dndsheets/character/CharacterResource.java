package it.fsisca.dndsheets.character;

import io.quarkus.security.Authenticated;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.character.dto.CharacterPayload;
import it.fsisca.dndsheets.character.dto.CharacterResponse;
import it.fsisca.dndsheets.character.dto.CharacterSummary;
import it.fsisca.dndsheets.character.model.SpellEntry;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.spells.SpellCatalogService;
import it.fsisca.dndsheets.spells.SpellEntryExpander;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;

/**
 * Endpoint CRUD per le schede personaggio dell'utente loggato.
 * Tutti gli endpoint richiedono JWT valido. Una scheda di un altro utente
 * risulta come 404 (non 403) per non leakare la sua esistenza.
 */
@Path("/characters")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "characters", description = "Schede personaggio D&D 5e")
public class CharacterResource {

    @Inject JsonWebToken             jwt;
    @Inject CharacterService         characterService;
    @Inject CharacterPortraitService portraitService;
    @Inject SpellCatalogService      spellCatalogService;

    /** Costruisce la response espandendo le spell del catalogo. */
    private CharacterResponse toResponse(Character c) {
        List<SpellEntry> expanded = SpellEntryExpander.expand(c.spells, spellCatalogService);
        return CharacterResponse.from(c, expanded);
    }

    @GET
    public List<CharacterSummary> list() {
        return characterService.listForOwner(currentUser()).stream()
                .map(CharacterSummary::from)
                .toList();
    }

    @POST
    public Response create(@Valid CharacterPayload payload) {
        Character c = characterService.create(currentUser(), payload);
        return Response.status(Response.Status.CREATED)
                .entity(toResponse(c))
                .build();
    }

    @GET
    @Path("/{id}")
    public CharacterResponse get(@PathParam("id") String id) {
        return toResponse(characterService.get(currentUser(), id));
    }

    @PATCH
    @Path("/{id}")
    public CharacterResponse update(@PathParam("id") String id, @Valid CharacterPayload payload) {
        return toResponse(characterService.update(currentUser(), id, payload));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        characterService.delete(currentUser(), id);
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/duplicate")
    @Consumes(MediaType.WILDCARD)
    public Response duplicate(@PathParam("id") String id) {
        Character copy = characterService.duplicate(currentUser(), id);
        return Response.status(Response.Status.CREATED)
                .entity(toResponse(copy))
                .build();
    }

    // ---------------- Portrait (GridFS) ----------------

    @POST
    @Path("/{id}/portrait")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public CharacterResponse uploadPortrait(@PathParam("id") String id,
                                            @RestForm("file") FileUpload file) {
        if (file == null) {
            throw AppException.badRequest("MISSING_FILE", "Manca il campo 'file'");
        }
        Character ch = characterService.get(currentUser(), id);
        portraitService.upload(ch, file.uploadedFile(), file.contentType(), file.size(), file.fileName());
        return toResponse(ch);
    }

    @GET
    @Path("/{id}/portrait")
    @Produces(MediaType.WILDCARD)
    public Response downloadPortrait(@PathParam("id") String id) {
        Character ch = characterService.get(currentUser(), id);
        var dl = portraitService.download(ch);
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
    @Path("/{id}/portrait")
    public Response deletePortrait(@PathParam("id") String id) {
        Character ch = characterService.get(currentUser(), id);
        portraitService.delete(ch);
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
