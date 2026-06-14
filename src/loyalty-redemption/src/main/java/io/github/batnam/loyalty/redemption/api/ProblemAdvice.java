package io.github.batnam.loyalty.redemption.api;

import io.github.batnam.loyalty.redemption.error.RedemptionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders domain errors as RFC-7807 Problem responses with a Loyalty-specific {@code code}
 * (loyalty-redemption.yaml {@code Problem} schema, e.g. {@code INSUFFICIENT_BALANCE},
 * {@code INVENTORY_EXHAUSTED}, {@code MISSING_APPROVAL}). {@link RedemptionException} carries its own
 * status/code; everything else maps to a generic 400.
 */
@RestControllerAdvice
public class ProblemAdvice {

    @ExceptionHandler(RedemptionException.class)
    public ProblemDetail onRedemption(RedemptionException ex) {
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
