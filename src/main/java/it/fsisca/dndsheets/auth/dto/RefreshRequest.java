package it.fsisca.dndsheets.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body di POST /auth/refresh e POST /auth/logout. */
public record RefreshRequest(@NotBlank String refreshToken) {}
