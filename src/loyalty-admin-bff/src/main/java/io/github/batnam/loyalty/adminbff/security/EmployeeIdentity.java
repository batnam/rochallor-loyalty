package io.github.batnam.loyalty.adminbff.security;

import io.github.batnam.loyalty.adminbff.error.BffException;

import java.util.Set;

/**
 * The authenticated bank employee behind a request, resolved from the request parameters the gateway
 * Authentication Service injects after verifying the employee token (the BFF does no token handling).
 *
 * <p>{@link #userId()} is the employee user id (forwarded downstream as {@code X-Actor} for the
 * hash-chained audit trail); {@link #roles()} are the Loyalty roles that gate operations (Arch §7.1):
 * {@code loyalty-cs-maker}, {@code loyalty-cs-checker}, {@code loyalty-campaign-manager},
 * {@code loyalty-fraud-ops}, {@code loyalty-admin}, {@code loyalty-readonly}. Per-Program scope
 * (PROGRAM_ADMIN assignment) is enforced upstream; this BFF gates by role.
 */
public record EmployeeIdentity(String userId, Set<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role) || roles.contains(Roles.ADMIN);
    }

    /** Throw {@code 403} unless the caller holds one of {@code allowed} (or {@code loyalty-admin}). */
    public void requireAnyRole(String... allowed) {
        for (String role : allowed) {
            if (hasRole(role)) {
                return;
            }
        }
        throw BffException.forbidden("requires one of role(s): " + String.join(", ", allowed));
    }
}
