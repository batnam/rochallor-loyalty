package io.github.batnam.loyalty.adminbff.error;

import org.springframework.http.HttpStatus;

/**
 * Edge error carrying the HTTP status and a Loyalty-specific {@code code} the REST layer renders as an
 * RFC-7807 Problem (loyalty-admin-bff.yaml {@code Problem} schema, e.g. {@code CAP_EXCEEDED},
 * {@code DSL_INVALID}, {@code MISSING_APPROVAL}). Upstream errors are translated into this type by
 * {@code UpstreamErrorHandler}; role/scope failures raise a {@code 403}.
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

    public static BffException forbidden(String detail) {
        return new BffException(HttpStatus.FORBIDDEN, "FORBIDDEN", detail);
    }
}
