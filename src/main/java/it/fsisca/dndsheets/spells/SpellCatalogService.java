package it.fsisca.dndsheets.spells;

import com.mongodb.client.model.Sorts;
import io.quarkus.mongodb.panache.PanacheQuery;
import it.fsisca.dndsheets.common.AppException;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Query sul catalogo di incantesimi. Sola lettura: il seeder e' l'unico
 * responsabile dei dati.
 */
@ApplicationScoped
public class SpellCatalogService {

    /** Page size massimo per evitare di scaricare l'intero catalogo. */
    public static final int MAX_LIMIT = 100;

    /**
     * Ricerca con filtri opzionali. Tutti i parametri sono nullable.
     *
     * @param q             stringa di ricerca case-insensitive sul nome,
     *                      sia EN canonico ({@code name}) sia traduzione IT
     *                      ({@code translations.it.name}). Match in qualunque
     *                      posizione.
     * @param level         livello esatto (0 = trucchetto)
     * @param school        scuola in EN (case-insensitive, match esatto)
     * @param className     classe in EN (case-insensitive, match esatto su elemento)
     * @param ritual        se non null, filtra per ritual={true|false}
     * @param concentration se non null, filtra per concentration={true|false}
     * @param offset        0-based
     * @param limit         max {@link #MAX_LIMIT}
     */
    public List<SpellCatalogEntry> search(String q, Integer level, String school, String className,
                                          Boolean ritual, Boolean concentration,
                                          int offset, int limit) {
        if (limit <= 0)         limit = 20;
        if (limit > MAX_LIMIT)  limit = MAX_LIMIT;
        if (offset < 0)         offset = 0;

        Document filter = buildFilter(q, level, school, className, ritual, concentration);

        Bson sort = Sorts.orderBy(Sorts.ascending("level"), Sorts.ascending("name"));
        PanacheQuery<SpellCatalogEntry> query = SpellCatalogEntry.find(filter, sort);
        // page(0, n) carica i primi n; per offset arbitrario usiamo range size.
        if (offset == 0) {
            return query.range(0, limit - 1).list();
        }
        return query.range(offset, offset + limit - 1).list();
    }

    /** Conta totale per la search (per i contatori UI), stessi filtri. */
    public long count(String q, Integer level, String school, String className,
                      Boolean ritual, Boolean concentration) {
        Document filter = buildFilter(q, level, school, className, ritual, concentration);
        return SpellCatalogEntry.count(filter);
    }

    /**
     * Costruisce il filtro Mongo unificato per search e count.
     * La ricerca testuale (q) usa {@code $or} su EN canonico e traduzione IT
     * cosi' che l'utente possa cercare sia "fire" sia "palla di fuoco".
     */
    private Document buildFilter(String q, Integer level, String school, String className,
                                 Boolean ritual, Boolean concentration) {
        Document filter = new Document();
        if (q != null && !q.isBlank()) {
            String safe = Pattern.quote(q.trim());
            Document regex = new Document("$regex", safe).append("$options", "i");
            filter.append("$or", List.of(
                    new Document("name", regex),
                    new Document("translations.it.name", regex)
            ));
        }
        if (level != null) {
            filter.append("level", level);
        }
        if (school != null && !school.isBlank()) {
            filter.append("school", new Document("$regex", "^" + Pattern.quote(school.trim()) + "$")
                    .append("$options", "i"));
        }
        if (className != null && !className.isBlank()) {
            filter.append("classes", new Document("$regex", "^" + Pattern.quote(className.trim()) + "$")
                    .append("$options", "i"));
        }
        if (ritual != null) {
            filter.append("ritual", ritual);
        }
        if (concentration != null) {
            filter.append("concentration", concentration);
        }
        return filter;
    }

    /**
     * Dettaglio singolo. Throws 404 se non esiste.
     */
    public SpellCatalogEntry getBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw AppException.notFound("SPELL_NOT_FOUND", "Incantesimo non trovato");
        }
        SpellCatalogEntry e = SpellCatalogEntry.<SpellCatalogEntry>find("slug", slug).firstResult();
        if (e == null) {
            throw AppException.notFound("SPELL_NOT_FOUND", "Incantesimo non trovato");
        }
        return e;
    }

    /**
     * Lookup batch per slug — usato dall'espansione spell sulle schede.
     * Restituisce una lista; gli slug non trovati sono semplicemente assenti
     * dal risultato (no exception).
     */
    public List<SpellCatalogEntry> findAllBySlugs(List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) return List.of();
        return SpellCatalogEntry.<SpellCatalogEntry>find("slug in ?1", slugs).list();
    }
}
