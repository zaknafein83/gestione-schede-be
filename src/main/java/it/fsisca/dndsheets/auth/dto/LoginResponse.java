package it.fsisca.dndsheets.auth.dto;

/**
 * Risposta di POST /auth/login e POST /auth/refresh.
 * Contiene i due token + (al login) la vista utente.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        UserResponse user
) {}
