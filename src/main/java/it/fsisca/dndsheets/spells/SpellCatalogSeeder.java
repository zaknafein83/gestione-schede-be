package it.fsisca.dndsheets.spells;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import it.fsisca.dndsheets.spells.model.SpellComponents;
import it.fsisca.dndsheets.spells.model.SpellTranslation;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Popola la collection spell_catalog all'avvio dell'app, se vuota.
 * Sorgente: resource {@code srd-spells.json} (319 spell SRD 5.1, CC-BY-4.0).
 * Idempotente: se la collection ha già voci, non fa nulla.
 */
@Startup(value = 10) // dopo MongoIndexes (priority 0 default)
@ApplicationScoped
public class SpellCatalogSeeder {

    private static final Logger LOG = Logger.getLogger(SpellCatalogSeeder.class);
    private static final String RESOURCE = "srd-spells.json";
    /** Lingue per cui caricare le traduzioni dal classpath: srd-spells.{lang}.json */
    private static final List<String> LANGS = List.of("it");

    @Inject ObjectMapper mapper;

    @PostConstruct
    void seed() {
        long existing = SpellCatalogEntry.count();
        if (existing > 0) {
            LOG.infof("Spell catalog già popolato (%d entry), seeder skip", existing);
            return;
        }

        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.warnf("Resource %s non trovata, catalogo resta vuoto", RESOURCE);
                return;
            }
            JsonNode root = mapper.readTree(in);
            if (!root.isArray()) {
                LOG.warnf("Resource %s non è un array JSON, skip", RESOURCE);
                return;
            }

            // Carica le traduzioni: Map<lang, Map<slug, SpellTranslation>>
            Map<String, Map<String, SpellTranslation>> translationsByLang = new HashMap<>();
            for (String lang : LANGS) {
                Map<String, SpellTranslation> map = loadTranslations(lang);
                if (!map.isEmpty()) {
                    translationsByLang.put(lang, map);
                    LOG.infof("Caricate %d traduzioni per lingua '%s'", map.size(), lang);
                }
            }

            List<SpellCatalogEntry> entries = new ArrayList<>(root.size());
            for (JsonNode item : root) {
                SpellCatalogEntry e = toEntity(item);
                // mergia traduzioni per slug
                for (var entry : translationsByLang.entrySet()) {
                    SpellTranslation tr = entry.getValue().get(e.slug);
                    if (tr != null) {
                        e.translations.put(entry.getKey(), tr);
                    }
                }
                entries.add(e);
            }
            SpellCatalogEntry.persist(entries);
            LOG.infof("Spell catalog seedato: %d entry", entries.size());
        } catch (Exception e) {
            LOG.errorf(e, "Errore caricamento %s, catalogo potrebbe essere parziale", RESOURCE);
        }
    }

    /**
     * Carica srd-spells.{lang}.json se presente. Formato:
     * {"srd:fireball": {"name": "...", "description": "...", ...}, ...}
     */
    private Map<String, SpellTranslation> loadTranslations(String lang) {
        String resource = "srd-spells." + lang + ".json";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOG.debugf("Resource %s non trovata, nessuna traduzione per '%s'", resource, lang);
                return Map.of();
            }
            JsonNode root = mapper.readTree(in);
            if (!root.isObject()) {
                LOG.warnf("Resource %s non è un object JSON, skip", resource);
                return Map.of();
            }
            Map<String, SpellTranslation> out = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                out.put(entry.getKey(), toTranslation(entry.getValue()));
            }
            return out;
        } catch (Exception e) {
            LOG.errorf(e, "Errore caricamento %s", resource);
            return Map.of();
        }
    }

    private static SpellTranslation toTranslation(JsonNode n) {
        SpellTranslation t = new SpellTranslation();
        t.name                = textOrNull(n, "name");
        t.description         = textOrNull(n, "description");
        t.atHigherLevels      = textOrNull(n, "atHigherLevels");
        t.school              = textOrNull(n, "school");
        t.castingTime         = textOrNull(n, "castingTime");
        t.range               = textOrNull(n, "range");
        t.duration            = textOrNull(n, "duration");
        t.materialDescription = textOrNull(n, "materialDescription");
        return t;
    }

    private static SpellCatalogEntry toEntity(JsonNode item) {
        SpellCatalogEntry e = new SpellCatalogEntry();
        e.slug           = textOrNull(item, "id");
        e.name           = textOrNull(item, "name");
        e.level          = item.has("level") ? item.get("level").asInt() : 0;
        e.school         = textOrNull(item, "school");
        e.castingTime    = textOrNull(item, "castingTime");
        e.range          = textOrNull(item, "range");
        e.duration       = textOrNull(item, "duration");
        e.concentration  = item.path("concentration").asBoolean(false);
        e.ritual         = item.path("ritual").asBoolean(false);
        e.description    = textOrNull(item, "description");
        e.atHigherLevels = textOrNull(item, "atHigherLevels");
        e.source         = textOrNull(item, "source");

        SpellComponents c = new SpellComponents();
        JsonNode ci = item.path("components");
        if (!ci.isMissingNode()) {
            c.verbal              = ci.path("verbal").asBoolean(false);
            c.somatic             = ci.path("somatic").asBoolean(false);
            c.material            = ci.path("material").asBoolean(false);
            c.materialDescription = textOrNull(ci, "materialDescription");
        }
        e.components = c;

        if (item.has("classes") && item.get("classes").isArray()) {
            for (JsonNode cls : item.get("classes")) {
                e.classes.add(cls.asText());
            }
        }
        return e;
    }

    private static String textOrNull(JsonNode n, String key) {
        if (n == null || !n.has(key) || n.get(key).isNull()) return null;
        String s = n.get(key).asText();
        return s.isEmpty() ? null : s;
    }
}
