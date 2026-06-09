package it.fsisca.dndsheets.payment.dto;

/**
 * Risposta a {@code POST /me/billing/checkout}. Il frontend fa
 * {@code launchUrl(url)} per portare l'utente sulla pagina di checkout.
 */
public record CheckoutResponse(String url) {}
