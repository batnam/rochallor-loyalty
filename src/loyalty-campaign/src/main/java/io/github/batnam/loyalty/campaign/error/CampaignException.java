package io.github.batnam.loyalty.campaign.error;

import org.springframework.http.HttpStatus;

/**
 * Domain error carrying the HTTP status and a Loyalty-specific {@code code} the REST layer renders as an
 * RFC-7807 Problem (loyalty-campaign.yaml {@code Problem} schema, e.g. {@code DRAWING_CLOSED},
 * {@code ILLEGAL_TRANSITION}, {@code MISSING_APPROVAL}).
 */
public class CampaignException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CampaignException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static CampaignException notFound(String code, String detail) {
        return new CampaignException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static CampaignException conflict(String code, String detail) {
        return new CampaignException(HttpStatus.CONFLICT, code, detail);
    }

    public static CampaignException badRequest(String code, String detail) {
        return new CampaignException(HttpStatus.BAD_REQUEST, code, detail);
    }
}
