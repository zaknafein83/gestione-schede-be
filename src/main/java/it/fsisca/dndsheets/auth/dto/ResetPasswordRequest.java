package it.fsisca.dndsheets.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Conferma reset password: l'utente arriva dalla mail con il token in chiaro
 * e fornisce la nuova password.
 *
 * <p>I requisiti sulla password sono allineati a {@code RegisterRequest}:
 * 10-100 caratteri, almeno una maiuscola e almeno una cifra (altrimenti il
 * reset permetterebbe password piu' deboli della registrazione).</p>
 */
public record ResetPasswordRequest(
        @NotBlank @Size(min = 32, max = 200) String token,

        @NotBlank
        @Size(min = 10, max = 100, message = "La password deve avere tra 10 e 100 caratteri")
        @Pattern(regexp = ".*[A-Z].*", message = "La password deve contenere almeno una lettera maiuscola")
        @Pattern(regexp = ".*\\d.*",   message = "La password deve contenere almeno un numero")
        String password
) {}
