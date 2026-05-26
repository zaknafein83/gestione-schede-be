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
 * <p>Endpoint protetti (solo POST):</p>
 * <ul>
 *   <li>{@code /auth/login}            — default 5/min/IP (anti credential stuffing)</li>
 *   <li>{@code /auth/forgot-password}  — default 3/min/IP (anti spam reset)</li>
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

    @Context jakarta.ws.rs.core.HttpHeaders headers;
    @Context jakarta.ws.rs.core.UriInfo     uriInfo;
    @Context io.vertx.ext.web.RoutingContext routing;  // per remoteAddress

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) return;

        String path = uriInfo.getPath();
        if (path.startsWith("/")) path = path.substring(1);

        int max;
        String key;
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
            default:
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
