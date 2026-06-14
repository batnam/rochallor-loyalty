package io.github.batnam.loyalty.core.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the {@link CoreException} factory methods: each maps to its HTTP status while carrying
 * the Loyalty-specific {@code code} (rendered as the RFC-7807 Problem {@code code}) and the detail.
 */
class CoreExceptionTest {

    @Test
    void notFoundMapsTo404() {
        CoreException ex = CoreException.notFound("MEMBER_NOT_FOUND", "no such member");
        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(ex.getMessage()).isEqualTo("no such member");
    }

    @Test
    void conflictMapsTo409() {
        CoreException ex = CoreException.conflict("BALANCE_INSUFFICIENT", "not enough points");
        assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.code()).isEqualTo("BALANCE_INSUFFICIENT");
    }

    @Test
    void badRequestMapsTo400() {
        CoreException ex = CoreException.badRequest("INVALID_AMOUNT", "must be positive");
        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("INVALID_AMOUNT");
    }
}
