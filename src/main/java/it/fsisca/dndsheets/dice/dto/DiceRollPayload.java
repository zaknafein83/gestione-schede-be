package it.fsisca.dndsheets.dice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload del client per registrare un tiro gia' eseguito.
 * Il backend non ricalcola: salva semplicemente quanto ricevuto.
 */
public record DiceRollPayload(
        @NotBlank @Size(max = 200) String formula,
        int                                  total,
        @Size(max = 1000) String             breakdown,
        boolean                              advantage,
        boolean                              disadvantage,
        /** ObjectId della scheda (hex string); null per tiri non legati a scheda. */
        @Size(max = 24) String               characterId
) {}
