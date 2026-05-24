package it.fsisca.dndsheets.spells.model;

/**
 * Traduzione di un incantesimo in una specifica lingua.
 * Tutti i campi sono opzionali: se {@code null} il client farà fallback
 * al testo inglese originale del catalogo.
 *
 * Le traduzioni sono opera originale del progetto, derivate dal testo SRD 5.1
 * (CC-BY-4.0). NON sono prese dal PHB italiano ufficiale (Asmodee/WotC, coperto
 * da copyright).
 */
public class SpellTranslation {
    public String name;
    public String description;
    public String atHigherLevels;
    public String school;
    public String castingTime;
    public String range;
    public String duration;
    public String materialDescription;
}
