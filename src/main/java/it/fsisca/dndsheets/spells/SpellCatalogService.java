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
     * @param q          stringa di ricerca case-insensitive su {@code name}
     *                   (match in qualunque posizione)
     * @param level      livello esatto (0 = trucchetto)
     * @param school     scuola in EN (case-insensitive, match esatto)
     * @param className  classe in EN (case-insensitive, match esatto su elemento della lista)
     * @param offset     0-based
     * @param limit      max {@link #MAX_LIMIT}
     */
    public List<SpellCatalogEntry> search(String q, Integer level, String school, String className,
                                          int offset, int limit) {
        if (limit <= 0)         limit = 20;
        if (limit > MAX_LIMIT)  limit = MAX_LIMIT;
        if (offset < 0)         offset = 0;

        Document filter = new Document();
        if (q != null && !q.isBlank()) {
            String safe = Pattern.quote(q.trim());
            filter.append("name", new Document("$regex", safe).append("$options", "i"));
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

        Bson sort = Sorts.orderBy(Sorts.ascending("level"), Sorts.ascending("name"));
        PanacheQuery<SpellCatalogEntry> query = SpellCatalogEntry.find(filter, sort);
        // page(0, n) carica i primi n; per offset arbitrario usiamo range size.
        if (offset == 0) {
            return query.range(0, limit - 1).list();
        }
        return query.range(offset, offset + limit - 1).list();
    }

    /** Conta totale per la search (per i contatori UI), stessi filtri. */
    public long count(String q, Integer level, String school, String className) {
        Document filter = new Document();
        if (q != null && !q.isBlank()) {
            filter.append("name", new Document("$regex", Pattern.quote(q.trim())).append("$options", "i"));
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
        return SpellCatalogEntry.count(filter);
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
