package io.github.batnam.loyalty.adminbff.security;

import io.github.batnam.loyalty.adminbff.error.BffException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for role gating. {@code loyalty-admin} is a wildcard over the functional roles, and
 * {@link EmployeeIdentity#requireAnyRole} raises a {@code 403} when none of the allowed roles is held.
 */
class EmployeeIdentityTest {

    private static EmployeeIdentity employee(String... roles) {
        return new EmployeeIdentity("emp-1", Set.of(roles));
    }

    @Test
    void hasRoleMatchesAnExplicitRole() {
        assertThat(employee(Roles.CAMPAIGN_MANAGER).hasRole(Roles.CAMPAIGN_MANAGER)).isTrue();
        assertThat(employee(Roles.READONLY).hasRole(Roles.CAMPAIGN_MANAGER)).isFalse();
    }

    @Test
    void adminIsAWildcardOverEveryRole() {
        EmployeeIdentity admin = employee(Roles.ADMIN);
        assertThat(admin.hasRole(Roles.FRAUD_OPS)).isTrue();
        assertThat(admin.hasRole(Roles.CS_MAKER)).isTrue();
        assertThatCode(() -> admin.requireAnyRole(Roles.FRAUD_OPS)).doesNotThrowAnyException();
    }

    @Test
    void requireAnyRolePassesWhenOneMatches() {
        assertThatCode(() -> employee(Roles.CS_CHECKER, Roles.READONLY)
                .requireAnyRole(Roles.CS_MAKER, Roles.CS_CHECKER)).doesNotThrowAnyException();
    }

    @Test
    void requireAnyRoleForbidsWhenNoneMatch() {
        assertThatThrownBy(() -> employee(Roles.READONLY).requireAnyRole(Roles.CAMPAIGN_MANAGER))
                .isInstanceOf(BffException.class)
                .satisfies(e -> {
                    assertThat(((BffException) e).status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((BffException) e).code()).isEqualTo("FORBIDDEN");
                });
    }
}
