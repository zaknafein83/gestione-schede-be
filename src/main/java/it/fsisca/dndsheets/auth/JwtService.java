package it.fsisca.dndsheets.auth;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Genera access JWT firmati con RS256 usando la chiave privata
 * configurata via {@code smallrye.jwt.sign.key.location}.
 */
@ApplicationScoped
public class JwtService {

    @ConfigProperty(name = "app.jwt.issuer")
    String issuer;

    @ConfigProperty(name = "app.jwt.access-token-duration")
    Duration accessTokenDuration;

    /**
     * Costruisce un access token per l'utente.
     * Claims:
     *  - sub: id Mongo (hex)
     *  - upn: email
     *  - groups: ["user"] + ["admin"] se l'utente ha il ruolo ADMIN
     *  - iss, iat, exp
     *
     * <p>I groups sono usati da Quarkus per {@code @RolesAllowed}. Convention
     * minuscolo per allineamento con il valore default "user".</p>
     */
    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        Set<String> groups = new HashSet<>();
        groups.add("user");
        if (user.isAdmin()) {
            groups.add("admin");
        }
        return Jwt.issuer(issuer)
                .subject(user.id.toHexString())
                .upn(user.email)
                .groups(groups)
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenDuration))
                .sign();
    }
}
