package it.fsisca.dndsheets.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload di {@code POST /auth/register}.
 * Le regole sulla password seguono la decisione di scope:
 * almeno 10 caratteri, almeno una maiuscola e almeno un numero.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,

        @NotBlank
        @Size(min = 10, max = 100, message = "La password deve avere tra 10 e 100 caratteri")
        @Pattern(regexp = ".*[A-Z].*", message = "La password deve contenere almeno una lettera maiuscola")
        @Pattern(regexp = ".*\\d.*",   message = "La password deve contenere almeno un numero")
        String password,

        @NotBlank
        @Size(min = 3, max = 30)
        @Pattern(regexp = "^[a-zA-Z0-9_]+$",
                 message = "Username puo' contenere solo lettere, numeri e underscore")
        String username,

        @NotBlank @Size(max = 60)
        String displayName
) {}
