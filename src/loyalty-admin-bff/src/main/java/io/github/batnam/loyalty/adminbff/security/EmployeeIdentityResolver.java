package io.github.batnam.loyalty.adminbff.security;

import io.github.batnam.loyalty.adminbff.error.BffException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves an {@link EmployeeIdentity} controller argument from the request parameters the gateway
 * Authentication Service injects <em>after</em> it has verified the employee token. The BFF does no
 * token handling itself (Arch §7.1).
 *
 * <p>Query parameters (not the body, not headers): {@code userId} is the employee user id — kept distinct
 * from the customer {@code customerId} that member-scoped endpoints act on — and {@code roles} is the
 * comma-separated set of Loyalty roles that gate operations. Query is used because the actor is needed on
 * every endpoint including reads (GET), which carry no body. A missing/blank {@code userId} is a
 * {@code 401} — defence in depth, since the request should never reach the BFF without it.
 */
@Component
public class EmployeeIdentityResolver implements HandlerMethodArgumentResolver {

    static final String USER_ID_PARAM = "userId";
    static final String ROLES_PARAM = "roles";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(EmployeeIdentity.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userId = webRequest.getParameter(USER_ID_PARAM);
        if (userId == null || userId.isBlank()) {
            throw BffException.unauthorized("missing " + USER_ID_PARAM + " request parameter");
        }
        return new EmployeeIdentity(userId, parseRoles(webRequest.getParameter(ROLES_PARAM)));
    }

    private static Set<String> parseRoles(String param) {
        if (param == null || param.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(param.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
