package it.fsisca.dndsheets.character;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import it.fsisca.dndsheets.character.model.LayoutWidget;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Layout personalizzato della dashboard di una scheda. Feature Premium:
 * solo gli utenti con {@code tier=PREMIUM} possono creare/aggiornare un
 * layout. La GET resta disponibile per tutti (utile per ritornare il layout
 * salvato in passato anche se l'utente nel frattempo e' tornato a FREE).
 *
 * <p>Relazione: 0..1 layout per {@code (ownerId, characterId)}. Garantito da
 * indice unico in {@link it.fsisca.dndsheets.common.MongoIndexes}.</p>
 */
@MongoEntity(collection = "character_layouts")
public class CharacterLayout extends PanacheMongoEntity {

    /** Owner del layout (e della scheda). */
    public ObjectId ownerId;

    /** Scheda a cui questo layout appartiene. */
    public ObjectId characterId;

    /** Versione dello schema del layout (per future migrations). Inizio a 1. */
    public int version = 1;

    /** Lista dei widget posizionati nel canvas. */
    public List<LayoutWidget> widgets = new ArrayList<>();

    public Instant createdAt;
    public Instant updatedAt;
}
