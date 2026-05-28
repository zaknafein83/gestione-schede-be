package it.fsisca.dndsheets.payment.dto;

/**
 * Risposta a {@code POST /me/stripe/checkout-session}.
 * Il frontend fa {@code window.location = url} per redirigere l'utente
 * sulla pagina hosted di Stripe Checkout.
 */
public record CheckoutSessionResponse(
        String sessionId,
        String url
) {}
