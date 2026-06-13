package io.github.batnam.loyalty.mobilebff.error;

import org.springframework.http.HttpStatus;

/**
 * Edge error carrying the HTTP status and a Loyalty-specific {@code code} the REST layer renders as an
 * RFC-7807 Problem (loyalty-mobile-bff.yaml {@code Problem} schema, e.g. {@code STEP_UP_REQUIRED},
 * {@code BALANCE_NEGATIVE}, {@code TCS_NOT_ACCEPTED}). Upstream errors are translated into this type by
 * {@code UpstreamErrorHandler} so the BFF never leaks an internal service's raw response.
 */
public class BffException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BffException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static BffException unauthorized(String detail) {
        return new BffException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", detail);
    }
}
