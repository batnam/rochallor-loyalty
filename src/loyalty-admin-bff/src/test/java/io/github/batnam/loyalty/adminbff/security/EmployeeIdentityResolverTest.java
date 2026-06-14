package io.github.batnam.loyalty.adminbff.security;

import io.github.batnam.loyalty.adminbff.error.BffException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EmployeeIdentityResolver}: it reads the employee identity from the request
 * parameters the gateway Authentication Service injects ({@code userId} + comma-separated {@code roles}),
 * does no token decoding, and rejects a request that arrives without the userId parameter.
 */
class EmployeeIdentityResolverTest {

    private final EmployeeIdentityResolver resolver = new EmployeeIdentityResolver();

    private EmployeeIdentity resolve(String userId, String roles) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (userId != null) {
            req.setParameter(EmployeeIdentityResolver.USER_ID_PARAM, userId);
        }
        if (roles != null) {
            req.setParameter(EmployeeIdentityResolver.ROLES_PARAM, roles);
        }
        return (EmployeeIdentity) resolver.resolveArgument(null, null, new ServletWebRequest(req), null);
    }

    @Test
    void readsUserIdAndCommaSeparatedRoles() {
        EmployeeIdentity id = resolve("mgr-1", "loyalty-campaign-manager, loyalty-readonly");
        assertThat(id.userId()).isEqualTo("mgr-1");
        assertThat(id.roles()).containsExactlyInAnyOrder("loyalty-campaign-manager", "loyalty-readonly");
    }

    @Test
    void absentRolesParamYieldsEmptySet() {
        EmployeeIdentity id = resolve("x", null);
        assertThat(id.roles()).isEmpty();
    }

    @Test
    void missingUserIdParamIsUnauthorized() {
        assertThatThrownBy(() -> resolve(null, "loyalty-cs-maker"))
                .isInstanceOf(BffException.class)
                .satisfies(e -> assertThat(((BffException) e).status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void blankUserIdParamIsUnauthorized() {
        assertThatThrownBy(() -> resolve("  ", "loyalty-cs-maker"))
                .isInstanceOf(BffException.class)
                .satisfies(e -> assertThat(((BffException) e).status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
