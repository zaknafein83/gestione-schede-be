package it.fsisca.dndsheets.character.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Payload per PUT /me/characters/{id}/layout — salvataggio completo
 * del layout (sostituisce eventuale layout esistente).
 */
public record CharacterLayoutPayload(

        /** Versione dello schema (informativa, non usata per locking al MVP). */
        Integer version,

        @NotNull
        @Size(max = 50)
        @Valid
        List<LayoutWidgetPayload> widgets

) {
    public record LayoutWidgetPayload(

            @NotBlank
            @Size(max = 32)
            String type,

            @Min(0)
            int x,

            @Min(0)
            int y,

            @Min(1)
            int w,

            @Min(1)
            int h,

            int z,

            @Size(max = 4000)
            String configJson

    ) {}
}
