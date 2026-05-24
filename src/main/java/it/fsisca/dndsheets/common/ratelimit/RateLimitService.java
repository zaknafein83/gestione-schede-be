package it.fsisca.dndsheets.common.ratelimit;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter in-memory a token bucket. Niente extension esterna,
 * niente Redis — sufficiente per il single-host single-process attuale.
 *
 * <p>Ogni chiave (es. {@code "login:1.2.3.4"}) ha un suo {@link TokenBucket}.
 * Il bucket si ricarica continuamente al ritmo di {@code maxTokens / minuto}
 * (cioè ogni {@code 60000/maxTokens} ms viene rigenerato 1 token).</p>
 *
 * <p>Cleanup pigro: quando la mappa supera {@link #CLEANUP_THRESHOLD} entries
 * vengono rimossi i bucket "pieni" (≥ {@code maxTokens}, nessuna richiesta
 * recente da limitare).</p>
 */
@ApplicationScoped
public class RateLimitService {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Tenta di consumare 1 token dal bucket. Ritorna 0 se ok, oppure i ms
     * residui prima del prossimo token disponibile se rate-limited.
     */
    public long tryConsume(String key, int maxPerMinute) {
        if (buckets.size() > CLEANUP_THRESHOLD) {
            cleanup(maxPerMinute);
        }
        TokenBucket b = buckets.computeIfAbsent(key, k -> new TokenBucket(maxPerMinute));
        return b.tryConsume(System.currentTimeMillis());
    }

    /** Solo per test: resetta tutti i bucket. */
    public void resetAll() {
        buckets.clear();
    }

    private void cleanup(int maxPerMinute) {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e -> e.getValue().isIdle(now, maxPerMinute));
    }

    /**
     * Token bucket thread-safe. Capacita' = {@code maxTokens}, refill continuo
     * a {@code maxTokens / 60000ms}.
     */
    static final class TokenBucket {
        private final double maxTokens;
        private final double tokensPerMillis;
        private double tokens;
        private long lastRefillMillis;

        TokenBucket(int maxPerMinute) {
            this.maxTokens       = maxPerMinute;
            this.tokensPerMillis = maxPerMinute / 60_000.0;
            this.tokens          = maxPerMinute;
            this.lastRefillMillis = System.currentTimeMillis();
        }

        synchronized long tryConsume(long nowMillis) {
            refill(nowMillis);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0L;
            }
            // ms necessari per accumulare il token mancante
            double need = 1.0 - tokens;
            return (long) Math.ceil(need / tokensPerMillis);
        }

        synchronized boolean isIdle(long nowMillis, int maxPerMinute) {
            refill(nowMillis);
            // Bucket pieno = nessun consumo recente da preservare.
            return tokens >= maxTokens;
        }

        private void refill(long nowMillis) {
            long elapsed = nowMillis - lastRefillMillis;
            if (elapsed <= 0) return;
            tokens = Math.min(maxTokens, tokens + elapsed * tokensPerMillis);
            lastRefillMillis = nowMillis;
        }
    }
}
