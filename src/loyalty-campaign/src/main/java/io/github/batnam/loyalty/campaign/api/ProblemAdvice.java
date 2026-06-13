package io.github.batnam.loyalty.campaign.api;

import io.github.batnam.loyalty.campaign.error.CampaignException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders domain errors as RFC-7807 Problem responses with a Loyalty-specific {@code code}
 * (loyalty-campaign.yaml {@code Problem} schema, e.g. {@code DRAWING_CLOSED}, {@code ILLEGAL_TRANSITION},
 * {@code MISSING_APPROVAL}). {@link CampaignException} carries its own status/code; everything else maps to
 * a generic 400.
 */
@RestControllerAdvice
public class ProblemAdvice {

    @ExceptionHandler(CampaignException.class)
    public ProblemDetail onCampaign(CampaignException ex) {
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
