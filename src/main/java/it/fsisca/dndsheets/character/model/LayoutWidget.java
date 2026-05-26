package it.fsisca.dndsheets.character.model;

import java.util.Set;

/**
 * Un widget posizionato nel canvas del layout dashboard di una scheda.
 * Coordinate in unità di griglia (snap-to-grid lato client, qui sono int
 * generici senza vincolo di griglia specifica). {@code configJson} è una
 * stringa JSON opzionale per parametri per-istanza (es. titolo custom).
 */
public class LayoutWidget {

    /** Tipi widget supportati. Validati server-side al save. */
    public static final Set<String> VALID_TYPES = Set.of(
            "ANAGRAFICA",
            "STATS",
            "ABILITIES",
            "SAVES",
            "COMBAT",
            "CONDITIONS",
            "SPELLCASTING",
            "EQUIP",
            "TRAITS",
            "NOTES",
            "HP_TRACKER",
            "PORTRAIT"
    );

    public String type;
    public int x;
    public int y;
    public int w;
    public int h;
    public int z;
    /** JSON serializzato di configurazione opzionale per-widget. Null se nessuno. */
    public String configJson;
}
