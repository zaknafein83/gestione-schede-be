package it.fsisca.dndsheets.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body di {@code POST /auth/google}.
 *
 * <p>{@code idToken} e' il JWT firmato emesso da Google e ottenuto lato client
 * via {@code google_sign_in}. Il backend lo verifica contro JWKS di Google
 * (firma, audience, issuer, expiration) e, in caso positivo, lo usa per
 * login/registrazione.</p>
 *
 * <p>{@code acceptPrivacy} e' la proof of consent GDPR analoga a
 * {@link RegisterRequest}. Solo al PRIMO accesso (utente nuovo, niente match
 * su googleSub o email) il service pretende {@code true}, rifiutando con
 * {@code 400 PRIVACY_NOT_ACCEPTED} altrimenti. Per i login successivi il campo
 * viene ignorato. Non usiamo {@code @AssertTrue} perche' deve poter essere
 * {@code false} per gli accessi successivi al primo.</p>
 */
public record GoogleLoginRequest(
        @NotBlank String idToken,
        boolean acceptPrivacy
) {}
