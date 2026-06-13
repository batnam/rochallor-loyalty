package io.github.batnam.loyalty.redemption.fulfil.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin client to the external Payment Hub used by {@code CashbackAdapter} (L3 §3.2). Disburses a cash
 * amount to a specific CASA and returns the Hub's disbursement reference (the saga's externalRef).
 * In-cluster this is mTLS; for local/test it is plain HTTP and stubbed by WireMock.
 *
 * <p>The Payment Hub is a Host Bank capability and only understands bank identifiers: it routes the
 * credit by {@code customerId} (the bank's CIF) and {@code accountNumber} (the target CASA — one CIF
 * may own several). It never sees Loyalty's internal {@code memberId}.
 */
@Component
public class PaymentHubClient {

    private final RestClient http;

    public PaymentHubClient(@Qualifier("paymentHubRestClient") RestClient paymentHubRestClient) {
        this.http = paymentHubRestClient;
    }

    public String disburse(long customerId, String accountNumber, long amount, String currency) {
        DisburseResponse resp = http.post()
                .uri("/disbursements")
                .body(new DisburseRequest(customerId, accountNumber, amount, currency))
                .retrieve()
                .body(DisburseResponse.class);
        if (resp == null || resp.externalRef() == null) {
            throw new IllegalStateException("Payment Hub returned no disbursement reference");
        }
        return resp.externalRef();
    }

    public record DisburseRequest(long customerId, String accountNumber, long amount, String currency) {
    }

    public record DisburseResponse(String externalRef) {
    }
}
