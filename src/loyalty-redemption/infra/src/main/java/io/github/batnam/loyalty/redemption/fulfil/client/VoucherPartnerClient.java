package io.github.batnam.loyalty.redemption.fulfil.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin client to the external 3rd-Party Voucher provider used by {@code ThirdPartyVoucherAdapter}
 * (L3 §3.3). Provisioning is asynchronous: the partner accepts the request (202) and returns a
 * correlation ref; the actual voucher code arrives later via the webhook → bridge → resume path. Stubbed
 * by WireMock in tests.
 */
@Component
public class VoucherPartnerClient {

    private final RestClient http;

    public VoucherPartnerClient(@Qualifier("voucherPartnerRestClient") RestClient voucherPartnerRestClient) {
        this.http = voucherPartnerRestClient;
    }

    /** Submit a provisioning request; returns the partner correlation ref the resume webhook carries. */
    public String provision(String sku, String customerRef) {
        ProvisionResponse resp = http.post()
                .uri("/provision")
                .body(new ProvisionRequest(sku, customerRef))
                .retrieve()
                .body(ProvisionResponse.class);
        if (resp == null || resp.externalRef() == null) {
            throw new IllegalStateException("voucher partner returned no correlation reference");
        }
        return resp.externalRef();
    }

    public record ProvisionRequest(String sku, String customerRef) {
    }

    public record ProvisionResponse(String externalRef) {
    }
}
