package io.github.batnam.loyalty.redemption.error;

import org.springframework.http.HttpStatus;

/**
 * Domain error carrying the HTTP status and a Loyalty-specific {@code code} the REST layer renders as
 * an RFC-7807 Problem (loyalty-redemption.yaml {@code Problem} schema, e.g. {@code ELIGIBILITY_REJECTED},
 * {@code INSUFFICIENT_BALANCE}, {@code MISSING_APPROVAL}).
 */
public class RedemptionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public RedemptionException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static RedemptionException notFound(String code, String detail) {
        return new RedemptionException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static RedemptionException conflict(String code, String detail) {
        return new RedemptionException(HttpStatus.CONFLICT, code, detail);
    }

    public static RedemptionException badRequest(String code, String detail) {
        return new RedemptionException(HttpStatus.BAD_REQUEST, code, detail);
    }
}
