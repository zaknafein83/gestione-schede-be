package it.fsisca.dndsheets.character;

import io.quarkus.panache.common.Sort;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.character.dto.CharacterPayload;
import it.fsisca.dndsheets.character.model.Coins;
import it.fsisca.dndsheets.character.model.SpellEntry;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.spells.SpellCatalogService;
import it.fsisca.dndsheets.spells.SpellEntryExpander;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Logica CRUD per le schede personaggio. Filtra sempre per {@code ownerId}
 * dell'utente loggato: una scheda di un altro utente e' invisibile (404,
 * non 403, per non leakare l'esistenza dell'id).
 */
@ApplicationScoped
public class CharacterService {

    @Inject CharacterPortraitService portraitService;
    @Inject SpellCatalogService      spellCatalogService;

    @ConfigProperty(name = "app.free-tier.max-characters", defaultValue = "2")
    long freeTierMaxCharacters;

    public List<Character> listForOwner(User owner) {
        return Character.<Character>find("ownerId", Sort.descending("updatedAt"), owner.id).list();
    }

    public Character create(User owner, CharacterPayload payload) {
        enforceTierLimit(owner);
        Character c = new Character();
        c.ownerId   = owner.id;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        applyPayload(c, payload);
        c.persist();
        return c;
    }

    /**
     * Verifica il limite di schede per utenti FREE. Premium e admin sono illimitati.
     * Lancia 402 PAYMENT_REQUIRED con codice TIER_LIMIT_REACHED se il limite e' raggiunto.
     */
    private void enforceTierLimit(User owner) {
        if (owner.isPremium() || owner.isAdmin()) return;
        long current = Character.count("ownerId", owner.id);
        if (current >= freeTierMaxCharacters) {
            throw AppException.paymentRequired("TIER_LIMIT_REACHED",
                    "Hai raggiunto il limite di " + freeTierMaxCharacters
                            + " schede del piano gratuito. Passa a Premium per crearne altre.");
        }
    }

    public Character get(User owner, String id) {
        ObjectId oid = parseId(id);
        Character c = Character.<Character>findById(oid);
        if (c == null || !owner.id.equals(c.ownerId)) {
            throw AppException.notFound("CHARACTER_NOT_FOUND", "Scheda non trovata");
        }
        return c;
    }

    public Character update(User owner, String id, CharacterPayload payload) {
        Character c = get(owner, id);
        applyPayload(c, payload);
        c.updatedAt = Instant.now();
        c.update();
        return c;
    }

    public void delete(User owner, String id) {
        Character c = get(owner, id);
        ObjectId portrait = c.portraitFileId;
        // Cascade: cancella layout custom della scheda (se presente).
        CharacterLayout.delete("characterId", c.id);
        c.delete();
        portraitService.deleteFileOnly(portrait);  // no-op se null
    }

    /**
     * Crea una copia indipendente di una scheda esistente. Se la scheda
     * sorgente ha un ritratto su GridFS, il file viene clonato (la copia
     * deve poter essere modificata/cancellata senza toccare l'originale).
     * Il nome diventa "<nome> (copia)"; se il nome era null resta null.
     */
    public Character duplicate(User owner, String id) {
        Character src = get(owner, id);
        enforceTierLimit(owner);

        Character dst = new Character();
        dst.ownerId   = owner.id;
        dst.createdAt = Instant.now();
        dst.updatedAt = dst.createdAt;

        // Anagrafica
        dst.name        = src.name == null ? null : src.name + " (copia)";
        dst.race        = src.race;
        dst.subrace     = src.subrace;
        dst.className   = src.className;
        dst.subclass    = src.subclass;
        dst.level       = src.level;
        dst.background  = src.background;
        dst.alignment   = src.alignment;
        dst.experience  = src.experience;
        dst.inspiration = src.inspiration;
        // portraitFileId: valorizzato dopo persist+clone

        // Ability
        dst.str   = src.str;
        dst.dex   = src.dex;
        dst.con   = src.con;
        dst.intel = src.intel;
        dst.wis   = src.wis;
        dst.cha   = src.cha;

        // Combat
        dst.armorClass          = src.armorClass;
        dst.initiative          = src.initiative;
        dst.speed               = src.speed;
        dst.hpMax               = src.hpMax;
        dst.hpCurrent           = src.hpCurrent;
        dst.hpTemp              = src.hpTemp;
        dst.hitDiceTotal        = src.hitDiceTotal;
        dst.hitDiceUsed         = src.hitDiceUsed;
        dst.deathSavesSuccesses = src.deathSavesSuccesses;
        dst.deathSavesFailures  = src.deathSavesFailures;
        dst.proficiencyBonus    = src.proficiencyBonus;

        // TS & skills (shallow: stesse entry, ma collezione esterna nuova)
        dst.savingThrows = src.savingThrows == null ? new LinkedHashMap<>() : new LinkedHashMap<>(src.savingThrows);
        dst.skills       = src.skills       == null ? new LinkedHashMap<>() : new LinkedHashMap<>(src.skills);

        // Attacchi/incantesimi
        dst.attacks           = src.attacks    == null ? new ArrayList<>() : new ArrayList<>(src.attacks);
        dst.spellcastingClass = src.spellcastingClass;
        dst.spellSaveDc       = src.spellSaveDc;
        dst.spellAttackBonus  = src.spellAttackBonus;
        dst.spellSlots        = src.spellSlots == null ? new LinkedHashMap<>() : new LinkedHashMap<>(src.spellSlots);
        dst.spells            = src.spells     == null ? new ArrayList<>() : new ArrayList<>(src.spells);

        // Equip
        dst.coins     = copyCoins(src.coins);
        dst.inventory = src.inventory == null ? new ArrayList<>() : new ArrayList<>(src.inventory);

        // Tratti
        dst.personalityTraits   = src.personalityTraits;
        dst.ideals              = src.ideals;
        dst.bonds               = src.bonds;
        dst.flaws               = src.flaws;
        dst.languages           = src.languages == null ? new ArrayList<>() : new ArrayList<>(src.languages);
        dst.armorProficiencies  = src.armorProficiencies;
        dst.weaponProficiencies = src.weaponProficiencies;
        dst.toolProficiencies   = src.toolProficiencies;
        dst.featuresAndTraits   = src.featuresAndTraits;

        // Note
        dst.backstory              = src.backstory;
        dst.alliesAndOrganizations = src.alliesAndOrganizations;
        dst.symbol                 = src.symbol;
        dst.physicalDescription    = src.physicalDescription;
        dst.notes                  = src.notes;

        dst.persist();

        // Clone del file portrait su GridFS (dopo persist: serve dst.id)
        if (src.portraitFileId != null) {
            ObjectId newPortraitId = portraitService.cloneFile(src.portraitFileId, dst);
            dst.portraitFileId = newPortraitId;
            dst.update();
        }

        return dst;
    }

    // ----- helpers -----

    private static Coins copyCoins(Coins src) {
        Coins c = new Coins();
        if (src == null) return c;
        c.cp = src.cp;
        c.sp = src.sp;
        c.ep = src.ep;
        c.gp = src.gp;
        c.pp = src.pp;
        return c;
    }


    // ----- helpers -----

    private ObjectId parseId(String id) {
        if (!ObjectId.isValid(id)) {
            throw AppException.notFound("CHARACTER_NOT_FOUND", "Scheda non trovata");
        }
        return new ObjectId(id);
    }

    /**
     * Applica i campi non-null del payload sull'entity. Per le collezioni:
     * se != null sostituiscono il valore corrente (per "azzerare" una lista
     * si invia [] esplicito, non null).
     */
    private void applyPayload(Character c, CharacterPayload p) {
        if (p.name()        != null) c.name        = trimOrNull(p.name());
        if (p.race()        != null) c.race        = trimOrNull(p.race());
        if (p.subrace()     != null) c.subrace     = trimOrNull(p.subrace());
        if (p.className()   != null) c.className   = trimOrNull(p.className());
        if (p.subclass()    != null) c.subclass    = trimOrNull(p.subclass());
        if (p.level()       != null) c.level       = p.level();
        if (p.background()  != null) c.background  = trimOrNull(p.background());
        if (p.alignment()   != null) c.alignment   = trimOrNull(p.alignment());
        if (p.experience()  != null) c.experience  = p.experience();
        if (p.inspiration() != null) c.inspiration = p.inspiration();

        if (p.str()   != null) c.str   = p.str();
        if (p.dex()   != null) c.dex   = p.dex();
        if (p.con()   != null) c.con   = p.con();
        if (p.intel() != null) c.intel = p.intel();
        if (p.wis()   != null) c.wis   = p.wis();
        if (p.cha()   != null) c.cha   = p.cha();

        if (p.armorClass()           != null) c.armorClass           = p.armorClass();
        if (p.initiative()           != null) c.initiative           = p.initiative();
        if (p.speed()                != null) c.speed                = p.speed();
        if (p.hpMax()                != null) c.hpMax                = p.hpMax();
        if (p.hpCurrent()            != null) c.hpCurrent            = p.hpCurrent();
        if (p.hpTemp()               != null) c.hpTemp               = p.hpTemp();
        if (p.hitDiceTotal()         != null) c.hitDiceTotal         = p.hitDiceTotal();
        if (p.hitDiceUsed()          != null) c.hitDiceUsed          = p.hitDiceUsed();
        if (p.deathSavesSuccesses()  != null) c.deathSavesSuccesses  = p.deathSavesSuccesses();
        if (p.deathSavesFailures()   != null) c.deathSavesFailures   = p.deathSavesFailures();
        if (p.proficiencyBonus()     != null) c.proficiencyBonus     = p.proficiencyBonus();

        if (p.savingThrows() != null) c.savingThrows = p.savingThrows();
        if (p.skills()       != null) c.skills       = p.skills();

        if (p.attacks()           != null) c.attacks           = p.attacks();
        if (p.spellcastingClass() != null) c.spellcastingClass = trimOrNull(p.spellcastingClass());
        if (p.spellSaveDc()       != null) c.spellSaveDc       = p.spellSaveDc();
        if (p.spellAttackBonus()  != null) c.spellAttackBonus  = p.spellAttackBonus();
        if (p.spellSlots()        != null) c.spellSlots        = p.spellSlots();
        if (p.spells()            != null) {
            c.spells = sanitizeSpellList(p.spells());
        }

        if (p.coins()     != null) c.coins     = p.coins();
        if (p.inventory() != null) c.inventory = p.inventory();

        if (p.conditions() != null) {
            // dedup mantenendo ordine
            var seen = new java.util.LinkedHashSet<String>();
            for (var s : p.conditions()) {
                if (s != null && !s.isBlank()) seen.add(s.trim());
            }
            c.conditions = new ArrayList<>(seen);
        }

        if (p.personalityTraits()   != null) c.personalityTraits   = p.personalityTraits();
        if (p.ideals()              != null) c.ideals              = p.ideals();
        if (p.bonds()               != null) c.bonds               = p.bonds();
        if (p.flaws()               != null) c.flaws               = p.flaws();
        if (p.languages()           != null) c.languages           = p.languages();
        if (p.armorProficiencies()  != null) c.armorProficiencies  = p.armorProficiencies();
        if (p.weaponProficiencies() != null) c.weaponProficiencies = p.weaponProficiencies();
        if (p.toolProficiencies()   != null) c.toolProficiencies   = p.toolProficiencies();
        if (p.featuresAndTraits()   != null) c.featuresAndTraits   = p.featuresAndTraits();

        if (p.backstory()              != null) c.backstory              = p.backstory();
        if (p.alliesAndOrganizations() != null) c.alliesAndOrganizations = p.alliesAndOrganizations();
        if (p.symbol()                 != null) c.symbol                 = p.symbol();
        if (p.physicalDescription()    != null) c.physicalDescription    = p.physicalDescription();
        if (p.notes()                  != null) c.notes                  = p.notes();
    }

    /**
     * Sanitizza la lista spell per la persistenza:
     *  - entry con {@code spellId} valido (esiste nel catalogo): scarta i
     *    campi snapshot, salva solo lo slug + i campi per-scheda;
     *  - entry con {@code spellId} assente o sconosciuto: tratta come
     *    custom (mantiene gli snapshot, azzera lo spellId orfano).
     *
     * Importare un JSON di scheda esportato da un'altra istanza con un
     * catalogo diverso degrada le spell orfane a custom (preserva i dati).
     */
    private java.util.List<SpellEntry> sanitizeSpellList(java.util.List<SpellEntry> input) {
        if (input == null || input.isEmpty()) return new ArrayList<>();

        java.util.List<String> slugs = input.stream()
                .filter(s -> s != null && s.spellId != null && !s.spellId.isBlank())
                .map(s -> s.spellId.trim())
                .distinct()
                .toList();
        java.util.Set<String> validSlugs = slugs.isEmpty()
                ? java.util.Set.of()
                : spellCatalogService.findAllBySlugs(slugs).stream()
                    .map(e -> e.slug)
                    .collect(java.util.stream.Collectors.toSet());

        java.util.List<SpellEntry> out = new ArrayList<>(input.size());
        for (SpellEntry s : input) {
            if (s == null) continue;
            if (s.spellId != null && !s.spellId.isBlank()) {
                String slug = s.spellId.trim();
                if (validSlugs.contains(slug)) {
                    SpellEntry kept = SpellEntryExpander.sanitizeForStorage(s);
                    if (kept != null) out.add(kept);
                    continue;
                }
                // slug orfano: degrada a custom, azzera spellId, mantiene snapshot
                s.spellId = null;
            }
            // Custom (o appena demoted): mantiene tutto come-è
            out.add(s);
        }
        return out;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
