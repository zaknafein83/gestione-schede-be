package it.fsisca.dndsheets.admin.dto;

import it.fsisca.dndsheets.auth.User;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Vista admin di un utente: include tier+roles ma mai password/hash. */
public record AdminUserSummary(
        String id,
        String email,
        String username,
        String displayName,
        boolean emailVerified,
        String tier,
        Instant premiumSince,
        String premiumSource,
        Set<String> roles,
        Instant createdAt,
        Instant updatedAt
) {
    public static AdminUserSummary from(User u) {
        return new AdminUserSummary(
                u.id == null ? null : u.id.toHexString(),
                u.email,
                u.username,
                u.displayName,
                u.emailVerified,
                u.tier == null ? User.TIER_FREE : u.tier,
                u.premiumSince,
                u.premiumSource,
                u.roles == null ? new HashSet<>() : u.roles,
                u.createdAt,
                u.updatedAt
        );
    }
}
