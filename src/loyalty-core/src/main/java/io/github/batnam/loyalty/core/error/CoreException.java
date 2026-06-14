package io.github.batnam.loyalty.core.error;

import org.springframework.http.HttpStatus;

/**
 * Domain error carrying the HTTP status and a Loyalty-specific {@code code} the REST layer renders as
 * an RFC-7807 Problem (see {@code loyalty-core.yaml} {@code Problem} schema).
 */
public class CoreException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CoreException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static CoreException notFound(String code, String detail) {
        return new CoreException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static CoreException conflict(String code, String detail) {
        return new CoreException(HttpStatus.CONFLICT, code, detail);
    }

    public static CoreException badRequest(String code, String detail) {
        return new CoreException(HttpStatus.BAD_REQUEST, code, detail);
    }
}
