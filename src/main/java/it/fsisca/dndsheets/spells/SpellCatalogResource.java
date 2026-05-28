package it.fsisca.dndsheets.spells;

import jakarta.annotation.security.PermitAll;
import it.fsisca.dndsheets.spells.dto.SpellDetail;
import it.fsisca.dndsheets.spells.dto.SpellSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Catalogo di incantesimi (read-only, pubblico).
 *
 * Endpoints:
 *   GET /spells               — search/list paginato
 *   GET /spells/count         — conteggio totale per la stessa search
 *   GET /spells/{slug}        — dettaglio singolo
 *
 * Pubblici per esporre il catalogo SRD anche dalla landing senza forzare
 * il login (i dati sono CC-BY-4.0 WotC, gia' liberi). Aggiunto
 * Cache-Control public 1h per ridurre il carico su navigatori ricorrenti
 * e su CDN intermedi (anche se oggi nginx non fa caching upstream).
 */
@Path("/spells")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
@Tag(name = "spells", description = "Catalogo incantesimi SRD (pubblico)")
public class SpellCatalogResource {

    /** Cache-Control: public, max-age=3600, immutable per il catalogo SRD statico. */
    private static final CacheControl PUBLIC_1H = buildCacheControl();

    private static CacheControl buildCacheControl() {
        CacheControl c = new CacheControl();
        c.setMaxAge(3600);
        c.setPrivate(false);
        c.setNoStore(false);
        c.setNoCache(false);
        return c;
    }

    @Inject SpellCatalogService service;

    @GET
    public Response search(
            @QueryParam("q")                                  String  q,
            @QueryParam("level")                              Integer level,
            @QueryParam("school")                             String  school,
            @QueryParam("className")                          String  className,
            @QueryParam("offset") @DefaultValue("0")          int     offset,
            @QueryParam("limit")  @DefaultValue("20")         int     limit) {
        List<SpellSummary> body = service.search(q, level, school, className, offset, limit).stream()
                .map(SpellSummary::from)
                .toList();
        return Response.ok(body).cacheControl(PUBLIC_1H).build();
    }

    @GET
    @Path("/count")
    public Response count(
            @QueryParam("q")         String  q,
            @QueryParam("level")     Integer level,
            @QueryParam("school")    String  school,
            @QueryParam("className") String  className) {
        long n = service.count(q, level, school, className);
        return Response.ok(n).cacheControl(PUBLIC_1H).build();
    }

    @GET
    @Path("/{slug}")
    public Response get(@PathParam("slug") String slug) {
        SpellDetail body = SpellDetail.from(service.getBySlug(slug));
        return Response.ok(body).cacheControl(PUBLIC_1H).build();
    }
}
