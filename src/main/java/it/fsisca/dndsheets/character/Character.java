package it.fsisca.dndsheets.character;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import it.fsisca.dndsheets.character.model.Attack;
import it.fsisca.dndsheets.character.model.Coins;
import it.fsisca.dndsheets.character.model.InventoryItem;
import it.fsisca.dndsheets.character.model.SkillEntry;
import it.fsisca.dndsheets.character.model.SpellEntry;
import it.fsisca.dndsheets.character.model.SpellSlot;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheda personaggio D&D 5e. Documento denormalizzato: tutto in uno solo
 * (sub-document). Nessun calcolo automatico al MVP-1 — i campi numerici
 * derivati (modificatori, CA finale, bonus competenza) saranno introdotti
 * in MVP-2. Qui l'utente puo' valorizzare manualmente tutto, anche in modo
 * incoerente, e completare a tappe.
 */
@MongoEntity(collection = "characters")
public class Character extends PanacheMongoEntity {

    /** Owner della scheda (riferimento a users._id). */
    public ObjectId ownerId;

    // ---------------- Anagrafica ----------------
    public String  name;
    public String  race;
    public String  subrace;
    public String  className;       // "class" e' parola riservata in Java
    public String  subclass;
    public Integer level;           // 1-20
    public String  background;
    public String  alignment;
    public Integer experience;
    public boolean inspiration;
    public ObjectId portraitFileId; // GridFS, null = nessun ritratto

    // ---------------- Ability scores ----------------
    public Integer str;
    public Integer dex;
    public Integer con;
    public Integer intel;           // "int" e' parola riservata
    public Integer wis;
    public Integer cha;

    // ---------------- Combattimento ----------------
    public Integer armorClass;
    public Integer initiative;
    public Integer speed;
    public Integer hpMax;
    public Integer hpCurrent;
    public Integer hpTemp;
    public Integer hitDiceTotal;
    public Integer hitDiceUsed;
    public Integer deathSavesSuccesses;   // 0-3
    public Integer deathSavesFailures;    // 0-3
    public Integer proficiencyBonus;

    // ---------------- TS & skills ----------------
    /** Tiri salvezza: chiavi suggerite "str","dex","con","int","wis","cha". */
    public Map<String, SkillEntry> savingThrows = new LinkedHashMap<>();
    /** Skill (18 skill di PHB 5e). Chiave = nome skill in inglese (acrobatics, athletics, ...). */
    public Map<String, SkillEntry> skills       = new LinkedHashMap<>();

    // ---------------- Attacchi / Incantesimi ----------------
    public List<Attack>           attacks    = new ArrayList<>();
    public String                 spellcastingClass;
    public Integer                spellSaveDc;
    public Integer                spellAttackBonus;
    /** Slot per livello (chiavi "1".."9"; BSON richiede chiavi String nelle Map). */
    public Map<String, SpellSlot> spellSlots = new LinkedHashMap<>();
    public List<SpellEntry>       spells     = new ArrayList<>();

    // ---------------- Equipaggiamento ----------------
    public Coins                 coins     = new Coins();
    public List<InventoryItem>   inventory = new ArrayList<>();

    // ---------------- Tratti ----------------
    public String       personalityTraits;
    public String       ideals;
    public String       bonds;
    public String       flaws;
    public List<String> languages           = new ArrayList<>();
    public String       armorProficiencies;
    public String       weaponProficiencies;
    public String       toolProficiencies;
    public String       featuresAndTraits;

    // ---------------- Stato (combat tracking) ----------------
    /** Condizioni PHB attive (chiavi en es. "blinded", "exhaustion"). */
    public List<String> conditions = new ArrayList<>();

    // ---------------- Note ----------------
    public String backstory;
    public String alliesAndOrganizations;
    public String symbol;
    public String physicalDescription;
    public String notes;

    // ---------------- Timestamp ----------------
    public Instant createdAt;
    public Instant updatedAt;
}
