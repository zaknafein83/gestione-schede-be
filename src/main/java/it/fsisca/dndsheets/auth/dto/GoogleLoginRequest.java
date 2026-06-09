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
 *
 * <p>{@code declareMinAge} e' l'autocertificazione di eta' minima (14 anni)
 * con la stessa semantica di {@code acceptPrivacy}: richiesta solo al primo
 * accesso, ignorata sui successivi. Non {@code @AssertTrue} per lo stesso
 * motivo. Il service rifiuta con {@code 400 AGE_NOT_DECLARED}.</p>
 *
 * <p>{@code username} e' opzionale: se valorizzato al PRIMO accesso (nuova
 * registrazione) viene usato come username dell'account, dopo validazione
 * formato/unicita' nel service. Se {@code null}/blank lo username viene
 * derivato automaticamente dall'email. Ignorato sui login successivi.</p>
 */
public record GoogleLoginRequest(
        @NotBlank String idToken,
        boolean acceptPrivacy,
        boolean declareMinAge,
        String username
) {}
