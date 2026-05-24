package it.fsisca.dndsheets.share.dto;

import it.fsisca.dndsheets.share.ShareToken;

import java.time.Instant;

/**
 * Vista di un token di condivisione. {@code token} e' valorizzato SOLO nella
 * risposta del POST di creazione (token in chiaro mostrato una volta sola).
 * Per le altre chiamate resta null — il client conserva il link ricevuto.
 */
public record ShareResponse(
        String  id,
        String  characterId,
        Instant createdAt,
        boolean revoked,
        /** Presente solo subito dopo la creazione (POST). */
        String  token
) {
    public static ShareResponse from(ShareToken t) {
        return new ShareResponse(
                t.id == null ? null : t.id.toHexString(),
                t.characterId == null ? null : t.characterId.toHexString(),
                t.createdAt,
                t.revokedAt != null,
                null
        );
    }

    public static ShareResponse fromWithToken(ShareToken t, String tokenPlain) {
        return new ShareResponse(
                t.id == null ? null : t.id.toHexString(),
                t.characterId == null ? null : t.characterId.toHexString(),
                t.createdAt,
                t.revokedAt != null,
                tokenPlain
        );
    }
}
