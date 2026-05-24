package it.fsisca.dndsheets.character.dto;

import it.fsisca.dndsheets.character.Character;

import java.time.Instant;

/**
 * Vista ridotta per GET /characters (lista delle schede dell'utente):
 * solo i campi mostrati in card di lista.
 */
public record CharacterSummary(
        String  id,
        String  name,
        String  race,
        String  className,
        Integer level,
        String  portraitFileId,
        Instant updatedAt
) {
    public static CharacterSummary from(Character c) {
        return new CharacterSummary(
                c.id == null ? null : c.id.toHexString(),
                c.name,
                c.race,
                c.className,
                c.level,
                c.portraitFileId == null ? null : c.portraitFileId.toHexString(),
                c.updatedAt
        );
    }
}
