package io.github.batnam.loyalty.adminbff.config;

import io.github.batnam.loyalty.adminbff.security.EmployeeIdentityResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers the {@link EmployeeIdentityResolver} so controllers can take an {@code EmployeeIdentity} argument. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final EmployeeIdentityResolver employeeIdentityResolver;

    public WebConfig(EmployeeIdentityResolver employeeIdentityResolver) {
        this.employeeIdentityResolver = employeeIdentityResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(employeeIdentityResolver);
    }
}
