package it.fsisca.dndsheets.profile.dto;

import jakarta.validation.constraints.Size;

/**
 * Payload di PATCH /me. Tutti i campi sono OPZIONALI:
 * solo i campi != null vengono aggiornati ("partial update").
 */
public record UpdateMeRequest(
        @Size(min = 1, max = 60) String displayName,
        @Size(max = 500)         String bio
) {}
