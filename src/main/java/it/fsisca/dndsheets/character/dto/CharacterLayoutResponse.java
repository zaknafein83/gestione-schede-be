package it.fsisca.dndsheets.character.dto;

import it.fsisca.dndsheets.character.CharacterLayout;
import it.fsisca.dndsheets.character.model.LayoutWidget;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Response per GET/PUT layout.
 *
 * <p>{@code isDefault=true} significa che l'utente non ha mai salvato un
 * layout custom per questa scheda — il client puo' usare la vista
 * "classica" a tab oppure un layout di default lato suo.</p>
 */
public record CharacterLayoutResponse(

        /** ID Mongo del layout salvato. Null se isDefault. */
        String id,
        boolean isDefault,
        int version,
        List<LayoutWidgetResponse> widgets,
        Instant createdAt,
        Instant updatedAt

) {

    public record LayoutWidgetResponse(
            String type,
            int x,
            int y,
            int w,
            int h,
            int z,
            String configJson
    ) {
        public static LayoutWidgetResponse from(LayoutWidget w) {
            return new LayoutWidgetResponse(w.type, w.x, w.y, w.w, w.h, w.z, w.configJson);
        }
    }

    public static CharacterLayoutResponse from(CharacterLayout l) {
        return new CharacterLayoutResponse(
                l.id.toHexString(),
                false,
                l.version,
                l.widgets.stream().map(LayoutWidgetResponse::from).toList(),
                l.createdAt,
                l.updatedAt
        );
    }

    /** Default layout: nessun widget custom, client decide come renderizzare. */
    public static CharacterLayoutResponse defaultLayout() {
        return new CharacterLayoutResponse(
                null,
                true,
                1,
                Collections.emptyList(),
                null,
                null
        );
    }
}
