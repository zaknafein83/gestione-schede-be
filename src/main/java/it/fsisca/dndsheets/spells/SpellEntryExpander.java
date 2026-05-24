package it.fsisca.dndsheets.spells;

import it.fsisca.dndsheets.character.model.SpellEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Trasforma la lista di {@link SpellEntry} memorizzata sulla scheda in una
 * lista "espansa" pronta per la response: per le entry che referenziano il
 * catalogo ({@code spellId} valorizzato) popola i campi snapshot dai dati
 * del catalogo.
 *
 * <p>Custom spell (spellId null) vengono restituite invariate. Entry con
 * {@code spellId} che non matcha alcun catalogo (es. cataloghi diversi tra
 * istanze) vengono restituite invariate — il client riceverà i pochi campi
 * disponibili.</p>
 */
public final class SpellEntryExpander {

    private SpellEntryExpander() {}

    public static List<SpellEntry> expand(List<SpellEntry> spells, SpellCatalogService cat) {
        if (spells == null || spells.isEmpty()) return spells;

        List<String> slugs = spells.stream()
                .filter(s -> s != null && s.spellId != null && !s.spellId.isBlank())
                .map(s -> s.spellId)
                .distinct()
                .toList();

        Map<String, SpellCatalogEntry> map = slugs.isEmpty()
                ? Map.of()
                : cat.findAllBySlugs(slugs).stream()
                    .collect(Collectors.toMap(e -> e.slug, e -> e));

        List<SpellEntry> out = new ArrayList<>(spells.size());
        for (SpellEntry s : spells) {
            if (s == null) continue;
            if (s.spellId == null || s.spellId.isBlank()) {
                out.add(s); // custom: passa cosi'
                continue;
            }
            SpellCatalogEntry c = map.get(s.spellId);
            if (c == null) {
                out.add(s); // orfana
                continue;
            }
            SpellEntry e = new SpellEntry();
            e.spellId        = s.spellId;
            e.prepared       = s.prepared;
            e.alwaysPrepared = s.alwaysPrepared;
            e.notes          = s.notes;
            e.name           = c.name;
            e.level          = c.level;
            e.school         = c.school;
            e.castingTime    = c.castingTime;
            e.range          = c.range;
            e.components     = c.components;
            e.duration       = c.duration;
            e.concentration  = c.concentration;
            e.ritual         = c.ritual;
            e.classes        = c.classes;
            e.description    = c.description;
            e.atHigherLevels = c.atHigherLevels;
            e.source         = c.source;
            out.add(e);
        }
        return out;
    }

    /**
     * Prepara una entry per la persistenza: se è un riferimento al catalogo
     * ({@code spellId} valorizzato), scarta i campi snapshot — restano nel
     * catalogo, sulla scheda salviamo solo lo slug e i campi per-scheda.
     * Per le custom (spellId null) restituisce l'input invariato.
     */
    public static SpellEntry sanitizeForStorage(SpellEntry input) {
        if (input == null) return null;
        if (input.spellId == null || input.spellId.isBlank()) {
            // Custom: lascia tutto. Se spellId era blank, normalizza a null.
            input.spellId = null;
            return input;
        }
        SpellEntry out = new SpellEntry();
        out.spellId        = input.spellId.trim();
        out.prepared       = input.prepared;
        out.alwaysPrepared = input.alwaysPrepared;
        out.notes          = input.notes;
        return out;
    }
}
