package it.fsisca.dndsheets.character.dto;

import it.fsisca.dndsheets.character.Character;
import it.fsisca.dndsheets.character.model.Attack;
import it.fsisca.dndsheets.character.model.Coins;
import it.fsisca.dndsheets.character.model.InventoryItem;
import it.fsisca.dndsheets.character.model.SkillEntry;
import it.fsisca.dndsheets.character.model.SpellEntry;
import it.fsisca.dndsheets.character.model.SpellSlot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Vista completa di una scheda — esposta da GET /characters/{id},
 * POST /characters, PATCH /characters/{id}.
 */
public record CharacterResponse(

        String  id,
        String  ownerId,

        // Anagrafica
        String  name,
        String  race,
        String  subrace,
        String  className,
        String  subclass,
        Integer level,
        String  background,
        String  alignment,
        Integer experience,
        boolean inspiration,
        String  portraitFileId,

        // Ability scores
        Integer str,
        Integer dex,
        Integer con,
        Integer intel,
        Integer wis,
        Integer cha,

        // Combat
        Integer armorClass,
        Integer initiative,
        Integer speed,
        Integer hpMax,
        Integer hpCurrent,
        Integer hpTemp,
        Integer hitDiceTotal,
        Integer hitDiceUsed,
        Integer deathSavesSuccesses,
        Integer deathSavesFailures,
        Integer proficiencyBonus,

        // TS & skills
        Map<String, SkillEntry> savingThrows,
        Map<String, SkillEntry> skills,

        // Attacchi/incantesimi
        List<Attack>             attacks,
        String                   spellcastingClass,
        Integer                  spellSaveDc,
        Integer                  spellAttackBonus,
        Map<String, SpellSlot>   spellSlots,
        List<SpellEntry>         spells,

        // Equip
        Coins                    coins,
        List<InventoryItem>      inventory,

        // Stato
        List<String>             conditions,

        // Tratti
        String       personalityTraits,
        String       ideals,
        String       bonds,
        String       flaws,
        List<String> languages,
        String       armorProficiencies,
        String       weaponProficiencies,
        String       toolProficiencies,
        String       featuresAndTraits,

        // Note
        String backstory,
        String alliesAndOrganizations,
        String symbol,
        String physicalDescription,
        String notes,

        // Timestamps
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Crea la response usando le {@code spells} già espanse (per le entry
     * con {@code spellId}, i campi snapshot sono popolati dal catalogo).
     * Usa la variante {@link #from(Character)} se preferisci spell raw.
     */
    public static CharacterResponse from(Character c, List<SpellEntry> expandedSpells) {
        return new CharacterResponse(
                c.id      == null ? null : c.id.toHexString(),
                c.ownerId == null ? null : c.ownerId.toHexString(),
                c.name, c.race, c.subrace, c.className, c.subclass, c.level,
                c.background, c.alignment, c.experience, c.inspiration,
                c.portraitFileId == null ? null : c.portraitFileId.toHexString(),
                c.str, c.dex, c.con, c.intel, c.wis, c.cha,
                c.armorClass, c.initiative, c.speed, c.hpMax, c.hpCurrent, c.hpTemp,
                c.hitDiceTotal, c.hitDiceUsed, c.deathSavesSuccesses, c.deathSavesFailures,
                c.proficiencyBonus,
                c.savingThrows, c.skills,
                c.attacks, c.spellcastingClass, c.spellSaveDc, c.spellAttackBonus,
                c.spellSlots, expandedSpells,
                c.coins, c.inventory,
                c.conditions,
                c.personalityTraits, c.ideals, c.bonds, c.flaws, c.languages,
                c.armorProficiencies, c.weaponProficiencies, c.toolProficiencies,
                c.featuresAndTraits,
                c.backstory, c.alliesAndOrganizations, c.symbol,
                c.physicalDescription, c.notes,
                c.createdAt, c.updatedAt);
    }

    /**
     * Variante senza espansione del catalogo — le entry con {@code spellId}
     * avranno i campi snapshot non valorizzati. Mantenuta per i test e per
     * casi in cui non interessa l'espansione.
     */
    public static CharacterResponse from(Character c) {
        return from(c, c.spells);
    }
}
