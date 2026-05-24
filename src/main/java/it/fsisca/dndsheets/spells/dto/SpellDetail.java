package it.fsisca.dndsheets.spells.dto;

import it.fsisca.dndsheets.spells.SpellCatalogEntry;
import it.fsisca.dndsheets.spells.model.SpellComponents;
import it.fsisca.dndsheets.spells.model.SpellTranslation;

import java.util.List;
import java.util.Map;

/**
 * Vista completa di un singolo incantesimo del catalogo.
 * Restituito da GET /spells/{id}.
 */
public record SpellDetail(
        String                         id,
        String                         name,
        Integer                        level,
        String                         school,
        String                         castingTime,
        String                         range,
        SpellComponents                components,
        String                         duration,
        boolean                        concentration,
        boolean                        ritual,
        List<String>                   classes,
        String                         description,
        String                         atHigherLevels,
        String                         source,
        Map<String, SpellTranslation>  translations
) {
    public static SpellDetail from(SpellCatalogEntry e) {
        return new SpellDetail(
                e.slug, e.name, e.level, e.school, e.castingTime, e.range,
                e.components, e.duration, e.concentration, e.ritual,
                e.classes == null ? List.of() : e.classes,
                e.description, e.atHigherLevels, e.source,
                e.translations == null ? Map.of() : e.translations);
    }
}
