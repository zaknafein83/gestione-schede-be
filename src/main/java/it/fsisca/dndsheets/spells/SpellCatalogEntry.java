package it.fsisca.dndsheets.spells;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import it.fsisca.dndsheets.spells.model.SpellComponents;
import it.fsisca.dndsheets.spells.model.SpellTranslation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Singolo incantesimo del catalogo (collection "spell_catalog").
 * Popolata dal {@link SpellCatalogSeeder} all'avvio con gli incantesimi
 * SRD 5.1 (CC-BY-4.0).
 *
 * Lo {@code slug} (es. "srd:fireball") è l'identificatore pubblico stabile,
 * referenziato da {@code SpellEntry.spellId} sulle schede personaggio.
 */
@MongoEntity(collection = "spell_catalog")
public class SpellCatalogEntry extends PanacheMongoEntity {

    /** Identificatore pubblico stabile: "srd:fireball", "custom:...". */
    public String slug;

    public String  name;
    /** 0 = trucchetto (cantrip), 1..9 = livello. */
    public Integer level;
    /** Scuola di magia in inglese: "Evocation", "Abjuration", ecc. */
    public String  school;
    public String  castingTime;
    public String  range;
    public SpellComponents components = new SpellComponents();
    public String  duration;
    public boolean concentration;
    public boolean ritual;
    /** Lista di classi che possono lanciare lo spell, nomi in EN ("Wizard"). */
    public List<String> classes = new ArrayList<>();
    /** Testo descrittivo principale (può contenere \n\n per separare paragrafi). */
    public String description;
    /** Testo "At Higher Levels", null se non applicabile. */
    public String atHigherLevels;
    /** Sorgente, es. "SRD 5.1". */
    public String source;

    /**
     * Traduzioni per lingua (chiave = codice ISO, es. "it").
     * Campi delle traduzioni sono opzionali; se un campo è null il client
     * fa fallback alla versione EN canonica sopra.
     */
    public Map<String, SpellTranslation> translations = new LinkedHashMap<>();
}
