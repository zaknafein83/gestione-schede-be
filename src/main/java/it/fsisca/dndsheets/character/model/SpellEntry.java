package it.fsisca.dndsheets.character.model;

import it.fsisca.dndsheets.spells.model.SpellComponents;

import java.util.List;

/**
 * Una entry "incantesimo" nella lista di una scheda personaggio.
 *
 * <h2>Due modalità</h2>
 * <ul>
 *   <li><b>Riferimento al catalogo</b> ({@code spellId} non null, es. {@code "srd:fireball"}):
 *       i campi snapshot ({@code name}, {@code level}, {@code school}, ...) restano
 *       {@code null} nel DB e vengono popolati al GET espandendo dal catalogo.</li>
 *   <li><b>Custom/homebrew</b> ({@code spellId} == null): tutti i campi sono
 *       salvati direttamente sulla scheda.</li>
 * </ul>
 *
 * <p>I campi <i>per-scheda</i> ({@code prepared}, {@code alwaysPrepared},
 * {@code notes}) sono sempre persistiti, in entrambe le modalità.</p>
 */
public class SpellEntry {

    /** Slug del catalogo, es. "srd:fireball". null = custom. */
    public String spellId;

    // ---- per-scheda (sempre) ----
    public boolean prepared;
    public boolean alwaysPrepared;
    public String  notes;

    // ---- snapshot (solo per custom; per SRD vengono espansi al GET) ----
    public String          name;
    public Integer         level;
    public String          school;
    public String          castingTime;
    public String          range;
    public SpellComponents components;
    public String          duration;
    public boolean         concentration;
    public boolean         ritual;
    public List<String>    classes;
    public String          description;
    public String          atHigherLevels;
    public String          source;
}
