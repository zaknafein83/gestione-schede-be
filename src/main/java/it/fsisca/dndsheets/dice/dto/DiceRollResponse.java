package it.fsisca.dndsheets.dice.dto;

import it.fsisca.dndsheets.dice.DiceRoll;

import java.time.Instant;

public record DiceRollResponse(
        String  id,
        String  ownerId,
        String  characterId,
        String  formula,
        int     total,
        String  breakdown,
        boolean advantage,
        boolean disadvantage,
        Instant createdAt
) {
    public static DiceRollResponse from(DiceRoll r) {
        return new DiceRollResponse(
                r.id == null ? null : r.id.toHexString(),
                r.ownerId == null ? null : r.ownerId.toHexString(),
                r.characterId == null ? null : r.characterId.toHexString(),
                r.formula,
                r.total,
                r.breakdown,
                r.advantage,
                r.disadvantage,
                r.createdAt
        );
    }
}
