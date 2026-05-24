package it.fsisca.dndsheets.auth.dto;

import it.fsisca.dndsheets.auth.User;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Vista pubblica di un utente — non include hash password n&eacute; campi sensibili. */
public record UserResponse(
        String id,
        String email,
        String username,
        String displayName,
        boolean emailVerified,
        String bio,
        Instant createdAt,
        String tier,
        Instant premiumSince,
        String premiumSource,
        Set<String> roles
) {
    public static UserResponse from(User u) {
        return new UserResponse(
                u.id == null ? null : u.id.toHexString(),
                u.email,
                u.username,
                u.displayName,
                u.emailVerified,
                u.bio,
                u.createdAt,
                u.tier == null ? User.TIER_FREE : u.tier,
                u.premiumSince,
                u.premiumSource,
                u.roles == null ? new HashSet<>() : u.roles
        );
    }
}
