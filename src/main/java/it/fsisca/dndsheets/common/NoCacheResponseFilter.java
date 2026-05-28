package it.fsisca.dndsheets.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

/**
 * Forza le risposte API a non essere messe in cache dal browser.
 *
 * <p>Il browser, in assenza di un {@code Cache-Control} esplicito, applica
 * euristiche di cache (es. tramite ETag/Last-Modified) che possono far
 * vedere all'utente dati stantii dopo un PATCH/PUT andato a buon fine —
 * specialmente quando si naviga rapidamente fra viste della stessa scheda
 * (classico ↔ layout custom). Settiamo esplicitamente
 * {@code Cache-Control: no-store} sulle risposte GET cosi' ogni accesso
 * forza un fetch verso il backend.</p>
 *
 * <p>Mantenuto solo per le GET: le altre operazioni (POST/PATCH/PUT/DELETE)
 * non sono cacheabili by HTTP spec e non necessitano dell'header.</p>
 *
 * <p>Eccezione: se il Resource ha gia' impostato esplicitamente
 * {@code Cache-Control} (es. dati statici pubblici come il catalogo SRD),
 * NON sovrascriviamo. L'endpoint ha la priorita' sul filter generico.</p>
 */
@Provider
public class NoCacheResponseFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request,
                       ContainerResponseContext response) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return;
        if (response.getHeaderString(HttpHeaders.CACHE_CONTROL) != null) {
            // L'endpoint ha gia' definito la sua policy: lasciamo stare.
            return;
        }
        response.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL,
                "no-store, must-revalidate");
        response.getHeaders().putSingle("Pragma", "no-cache");
    }
}
