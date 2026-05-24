package it.fsisca.dndsheets.character.dto;

import it.fsisca.dndsheets.character.model.Attack;
import it.fsisca.dndsheets.character.model.Coins;
import it.fsisca.dndsheets.character.model.InventoryItem;
import it.fsisca.dndsheets.character.model.SkillEntry;
import it.fsisca.dndsheets.character.model.SpellEntry;
import it.fsisca.dndsheets.character.model.SpellSlot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Payload usato sia da POST /characters (creazione) sia da PATCH /characters/{id}
 * (update parziale). Tutti i campi sono opzionali: solo i campi NON null
 * vengono applicati. Per le collezioni: se non-null sostituiscono il valore
 * corrente; per azzerare una lista si invia [], non null.
 */
public record CharacterPayload(

        // Anagrafica
        @Size(max = 80)  String  name,
        @Size(max = 80)  String  race,
        @Size(max = 80)  String  subrace,
        @Size(max = 80)  String  className,
        @Size(max = 80)  String  subclass,
        @Min(1) @Max(20) Integer level,
        @Size(max = 80)  String  background,
        @Size(max = 40)  String  alignment,
        @Min(0)          Integer experience,
                         Boolean inspiration,

        // Ability scores
        @Min(1) @Max(30) Integer str,
        @Min(1) @Max(30) Integer dex,
        @Min(1) @Max(30) Integer con,
        @Min(1) @Max(30) Integer intel,
        @Min(1) @Max(30) Integer wis,
        @Min(1) @Max(30) Integer cha,

        // Combat
                         Integer armorClass,
                         Integer initiative,
                         Integer speed,
        @Min(0)          Integer hpMax,
                         Integer hpCurrent,
                         Integer hpTemp,
        @Min(0)          Integer hitDiceTotal,
        @Min(0)          Integer hitDiceUsed,
        @Min(0) @Max(3)  Integer deathSavesSuccesses,
        @Min(0) @Max(3)  Integer deathSavesFailures,
        @Min(0)          Integer proficiencyBonus,

        // TS & skills
        Map<String, SkillEntry> savingThrows,
        Map<String, SkillEntry> skills,

        // Attacchi / incantesimi
        List<Attack>             attacks,
        @Size(max = 80)  String  spellcastingClass,
                         Integer spellSaveDc,
                         Integer spellAttackBonus,
        Map<String, SpellSlot>   spellSlots,
        List<SpellEntry>         spells,

        // Equip
        Coins                    coins,
        List<InventoryItem>      inventory,

        // Stato (combat tracking)
        List<String>             conditions,

        // Tratti
        @Size(max = 2000) String personalityTraits,
        @Size(max = 2000) String ideals,
        @Size(max = 2000) String bonds,
        @Size(max = 2000) String flaws,
        List<String>             languages,
        @Size(max = 500)  String armorProficiencies,
        @Size(max = 500)  String weaponProficiencies,
        @Size(max = 500)  String toolProficiencies,
        @Size(max = 5000) String featuresAndTraits,

        // Note
        @Size(max = 10000) String backstory,
        @Size(max = 2000)  String alliesAndOrganizations,
        @Size(max = 500)   String symbol,
        @Size(max = 2000)  String physicalDescription,
        @Size(max = 5000)  String notes
) {}
