package it.fsisca.dndsheets.share;

import it.fsisca.dndsheets.character.Character;
import it.fsisca.dndsheets.character.dto.CharacterResponse;
import it.fsisca.dndsheets.spells.SpellCatalogService;
import it.fsisca.dndsheets.spells.SpellEntryExpander;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Endpoint PUBBLICO per visualizzare una scheda condivisa via token.
 * Nessun login richiesto: chiunque conosca il token (link) puo' leggere.
 */
@Path("/share/{token}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
@Tag(name = "shares", description = "Visualizzazione pubblica scheda condivisa")
public class PublicShareResource {

    @Inject ShareService        service;
    @Inject SpellCatalogService spellCatalogService;

    @GET
    public CharacterResponse view(@PathParam("token") String token) {
        Character c = service.resolveTokenPublic(token);
        var expanded = SpellEntryExpander.expand(c.spells, spellCatalogService);
        return CharacterResponse.from(c, expanded);
    }
}
