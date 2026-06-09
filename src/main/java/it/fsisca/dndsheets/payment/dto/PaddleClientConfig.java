package it.fsisca.dndsheets.payment.dto;

/**
 * Config pubblica consumata dalla pagina statica {@code checkout.html} (e dal
 * frontend per decidere se mostrare il bottone "Acquista" o "presto disponibile").
 * Quando {@code enabled=false}, {@code clientToken} e {@code priceId} sono null.
 */
public record PaddleClientConfig(
        boolean enabled,
        String environment,
        String clientToken,
        String priceId
) {}
