package it.fsisca.dndsheets.character.model;

/**
 * Voce per un tiro salvezza o skill: solo i flag di competenza e un eventuale
 * valore personalizzato. I calcoli arriveranno in MVP-2.
 */
public class SkillEntry {
    public boolean proficient;
    public boolean expertise;
    /** Override manuale del valore finale; null = usa il default (in futuro calcolato). */
    public Integer customValue;
}
