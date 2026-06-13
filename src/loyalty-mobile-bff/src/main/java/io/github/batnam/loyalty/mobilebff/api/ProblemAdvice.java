package io.github.batnam.loyalty.mobilebff.api;

import io.github.batnam.loyalty.mobilebff.error.BffException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders edge errors as RFC-7807 Problem responses with a Loyalty-specific {@code code}
 * (loyalty-mobile-bff.yaml {@code Problem} schema). {@link BffException} carries its own status/code —
 * including statuses translated up from upstream services by {@code UpstreamErrorHandler}; anything else
 * maps to a generic 400.
 */
@RestControllerAdvice
public class ProblemAdvice {

    @ExceptionHandler(BffException.class)
    public ProblemDetail onBff(BffException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        pd.setProperty("code", ex.code());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setProperty("code", "BAD_REQUEST");
        return pd;
    }
}
