package it.fsisca.dndsheets.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Richiesta avvio reset password. Risposta sempre 204 anche se l'email non
 * esiste (no leak di esistenza account).
 */
public record ForgotPasswordRequest(
        @NotBlank @Email @Size(max = 254) String email
) {}
