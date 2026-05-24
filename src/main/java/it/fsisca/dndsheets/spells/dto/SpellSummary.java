package it.fsisca.dndsheets.spells.dto;

import it.fsisca.dndsheets.spells.SpellCatalogEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vista ridotta del catalogo: id, name, level, school, ritual, concentration, classes,
 * + translatedNames (lang → nome tradotto) per il picker IT/EN.
 * Usata per la lista risultati del picker (lo schema completo si carica solo
 * al dettaglio, evita di trasferire 400 KB per ogni search).
 */
public record SpellSummary(
        String              id,
        String              name,
        Integer             level,
        String              school,
        boolean             ritual,
        boolean             concentration,
        List<String>        classes,
        String              source,
        Map<String, String> translatedNames
) {
    public static SpellSummary from(SpellCatalogEntry e) {
        Map<String, String> names = new LinkedHashMap<>();
        if (e.translations != null) {
            for (var entry : e.translations.entrySet()) {
                if (entry.getValue() != null && entry.getValue().name != null) {
                    names.put(entry.getKey(), entry.getValue().name);
                }
            }
        }
        return new SpellSummary(
                e.slug, e.name, e.level, e.school,
                e.ritual, e.concentration,
                e.classes == null ? List.of() : e.classes,
                e.source,
                names);
    }
}
