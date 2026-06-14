package io.github.batnam.loyalty.mobilebff.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Saga-status → customer-status mapping. The customer enum
 * (loyalty-mobile-bff.yaml: PENDING/RESERVED/FULFILLING/COMPLETED/FAILED) is narrower than the Saga's
 * internal vocabulary, so the two collapsing cases (COMMITTED→COMPLETED, RELEASED→FAILED) and the
 * unknown-status fallback are the ones worth pinning.
 */
class RedemptionStatusMappingTest {

    @Test
    void committedBecomesCompleted() {
        assertThat(RedemptionClient.mapStatus("COMMITTED")).isEqualTo("COMPLETED");
    }

    @Test
    void releasedBecomesFailed() {
        assertThat(RedemptionClient.mapStatus("RELEASED")).isEqualTo("FAILED");
    }

    @Test
    void passThroughStatusesAreUnchanged() {
        assertThat(RedemptionClient.mapStatus("RESERVED")).isEqualTo("RESERVED");
        assertThat(RedemptionClient.mapStatus("FULFILLING")).isEqualTo("FULFILLING");
        assertThat(RedemptionClient.mapStatus("FAILED")).isEqualTo("FAILED");
        assertThat(RedemptionClient.mapStatus("PENDING")).isEqualTo("PENDING");
    }

    @Test
    void nullAndUnknownDefaultToPending() {
        assertThat(RedemptionClient.mapStatus(null)).isEqualTo("PENDING");
        assertThat(RedemptionClient.mapStatus("WAT")).isEqualTo("PENDING");
    }
}
