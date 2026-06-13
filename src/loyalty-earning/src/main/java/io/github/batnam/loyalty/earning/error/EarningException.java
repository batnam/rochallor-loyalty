package io.github.batnam.loyalty.earning.error;

import org.springframework.http.HttpStatus;

/**
 * Domain error carrying the HTTP status and a Loyalty-specific {@code code} the REST layer renders as
 * an RFC-7807 Problem (loyalty-earning.yaml {@code Problem} schema, e.g. {@code DSL_INVALID}).
 */
public class EarningException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public EarningException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static EarningException notFound(String code, String detail) {
        return new EarningException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static EarningException conflict(String code, String detail) {
        return new EarningException(HttpStatus.CONFLICT, code, detail);
    }

    public static EarningException badRequest(String code, String detail) {
        return new EarningException(HttpStatus.BAD_REQUEST, code, detail);
    }
}
