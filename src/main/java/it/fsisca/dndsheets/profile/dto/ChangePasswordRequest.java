package it.fsisca.dndsheets.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Payload di POST /me/change-password. */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,

        @NotBlank
        @Size(min = 10, max = 100)
        @Pattern(regexp = ".*[A-Z].*", message = "La password deve contenere almeno una lettera maiuscola")
        @Pattern(regexp = ".*\\d.*",   message = "La password deve contenere almeno un numero")
        String newPassword
) {}
