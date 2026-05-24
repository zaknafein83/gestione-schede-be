package it.fsisca.dndsheets.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Conferma reset password: l'utente arriva dalla mail con il token in chiaro
 * e fornisce la nuova password.
 */
public record ResetPasswordRequest(
        @NotBlank @Size(min = 32, max = 200) String token,
        @NotBlank @Size(min = 10, max = 100) String password
) {}
