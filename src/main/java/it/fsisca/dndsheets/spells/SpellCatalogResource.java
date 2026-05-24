package it.fsisca.dndsheets.spells;

import io.quarkus.security.Authenticated;
import it.fsisca.dndsheets.spells.dto.SpellDetail;
import it.fsisca.dndsheets.spells.dto.SpellSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Catalogo di incantesimi (read-only).
 *
 * Endpoints:
 *   GET /spells               — search/list paginato (auth)
 *   GET /spells/count         — conteggio totale per la stessa search (auth)
 *   GET /spells/{slug}        — dettaglio singolo (auth)
 *
 * Auth richiesta: i dati sono SRD pubblico ma vogliamo evitare scraping
 * incontrollato e mantenere consistenza con il resto delle API.
 */
@Path("/spells")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "spells", description = "Catalogo incantesimi SRD")
public class SpellCatalogResource {

    @Inject SpellCatalogService service;

    @GET
    public List<SpellSummary> search(
            @QueryParam("q")                                  String  q,
            @QueryParam("level")                              Integer level,
            @QueryParam("school")                             String  school,
            @QueryParam("className")                          String  className,
            @QueryParam("offset") @DefaultValue("0")          int     offset,
            @QueryParam("limit")  @DefaultValue("20")         int     limit) {
        return service.search(q, level, school, className, offset, limit).stream()
                .map(SpellSummary::from)
                .toList();
    }

    @GET
    @Path("/count")
    public long count(
            @QueryParam("q")         String  q,
            @QueryParam("level")     Integer level,
            @QueryParam("school")    String  school,
            @QueryParam("className") String  className) {
        return service.count(q, level, school, className);
    }

    @GET
    @Path("/{slug}")
    public SpellDetail get(@PathParam("slug") String slug) {
        return SpellDetail.from(service.getBySlug(slug));
    }
}
