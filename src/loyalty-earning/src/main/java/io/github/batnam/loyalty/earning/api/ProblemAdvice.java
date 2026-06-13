package io.github.batnam.loyalty.earning.api;

import io.github.batnam.loyalty.earning.error.EarningException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders domain errors as RFC-7807 Problem responses with a Loyalty-specific {@code code}
 * (loyalty-earning.yaml {@code Problem} schema). {@link EarningException} carries its own status/code
 * (e.g. {@code DSL_INVALID}); everything else maps to a generic 400.
 */
@RestControllerAdvice
public class ProblemAdvice {

    @ExceptionHandler(EarningException.class)
    public ProblemDetail onEarning(EarningException ex) {
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
