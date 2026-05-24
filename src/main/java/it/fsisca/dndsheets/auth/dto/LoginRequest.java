package it.fsisca.dndsheets.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body di POST /auth/login. */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {}
