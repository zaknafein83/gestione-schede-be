package it.fsisca.dndsheets.common.ratelimit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Rate-limit per IP su endpoint auth sensibili.
 *
 * <p>Endpoint protetti:</p>
 * <ul>
 *   <li>POST {@code /auth/login}, {@code /auth/google} — default 5/min/IP (anti credential stuffing)</li>
 *   <li>POST {@code /auth/forgot-password}             — default 3/min/IP (anti spam reset)</li>
 *   <li>POST {@code /auth/reset-password}              — default 5/min/IP (anti brute-force del token)</li>
 *   <li>GET  {@code /share/{token}}                    — default 120/min/IP (anti enumerazione token)</li>
 *   <li>GET  {@code /spells}, {@code /spells/...}      — default 120/min/IP (anti DoS sul catalogo pubblico)</li>
 * </ul>
 *
 * <p>Sopra il limite risponde 429 con body RFC 7807 e header
 * {@code Retry-After} in secondi. La porta in {@code app.rate-limit.*}
 * permette di alzare/abbassare via config senza redeploy.</p>
 */
@Provider
@PreMatching
@ApplicationScoped
public class RateLimitFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);
    public static final String PROBLEM_JSON = "application/problem+json";

    @Inject RateLimitService service;

    @ConfigProperty(name = "app.rate-limit.login.max-per-minute", defaultValue = "5")
    int loginMaxPerMinute;

    @ConfigProperty(name = "app.rate-limit.forgot-password.max-per-minute", defaultValue = "3")
    int forgotPasswordMaxPerMinute;

    @ConfigProperty(name = "app.rate-limit.reset-password.max-per-minute", defaultValue = "5")
    int resetPasswordMaxPerMinute;

    @ConfigProperty(name = "app.rate-limit.public.max-per-minute", defaultValue = "120")
    int publicMaxPerMinute;

    @Context jakarta.ws.rs.core.HttpHeaders headers;
    @Context jakarta.ws.rs.core.UriInfo     uriInfo;
    @Context io.vertx.ext.web.RoutingContext routing;  // per remoteAddress

    @Override
    public void filter(ContainerRequestContext ctx) {
        String method = ctx.getMethod();
        String path = uriInfo.getPath();
        if (path.startsWith("/")) path = path.substring(1);

        int max;
        String key;
        if ("POST".equalsIgnoreCase(method)) {
            switch (path) {
                case "auth/login":
                case "auth/google":
                    max = loginMaxPerMinute;
                    key = "login:" + clientIp();
                    break;
                case "auth/forgot-password":
                    max = forgotPasswordMaxPerMinute;
                    key = "forgot:" + clientIp();
                    break;
                case "auth/reset-password":
                    max = resetPasswordMaxPerMinute;
                    key = "reset:" + clientIp();
                    break;
                default:
                    return;
            }
        } else if ("GET".equalsIgnoreCase(method)) {
            // share/{token}: il token e' parte del path -> startsWith.
            if (path.startsWith("share/")) {
                max = publicMaxPerMinute;
                key = "share:" + clientIp();
            } else if (path.equals("spells") || path.startsWith("spells/")) {
                max = publicMaxPerMinute;
                key = "spells:" + clientIp();
            } else {
                return;
            }
        } else {
            return;
        }

        long retryAfterMillis = service.tryConsume(key, max);
        if (retryAfterMillis == 0L) return;

        long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);
        Map<String, Object> body = new HashMap<>();
        body.put("type",   "about:blank");
        body.put("title",  "Too Many Requests");
        body.put("status", 429);
        body.put("code",   "RATE_LIMITED");
        body.put("detail", "Troppe richieste, riprova tra " + retryAfterSeconds + " secondi.");

        LOG.debugf("Rate limit hit: key=%s retryAfter=%ds", key, retryAfterSeconds);

        ctx.abortWith(Response.status(429)
                .type(PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                .entity(body)
                .build());
    }

    /**
     * Risolve l'IP del client. Se c'e' {@code X-Forwarded-For} (nginx davanti),
     * usa il primo IP; altrimenti l'indirizzo remoto raw.
     */
    private String clientIp() {
        String xff = headers.getHeaderString("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma < 0 ? xff : xff.substring(0, comma)).trim();
        }
        String real = headers.getHeaderString("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        try {
            return routing.request().remoteAddress().host();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
