package it.fsisca.dndsheets.spells.model;

/**
 * Componenti per il lancio di un incantesimo D&D 5e.
 * Verbale, somatico, materiale. Se materiale, può avere descrizione del componente.
 */
public class SpellComponents {
    public boolean verbal;
    public boolean somatic;
    public boolean material;
    public String  materialDescription;
}
