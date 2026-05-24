package it.fsisca.dndsheets.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Conferma cancellazione account: richiede la password attuale.
 * L'azione è irreversibile (hard delete cascade).
 */
public record DeleteAccountRequest(
        @NotBlank @Size(min = 1, max = 200) String password
) {}
