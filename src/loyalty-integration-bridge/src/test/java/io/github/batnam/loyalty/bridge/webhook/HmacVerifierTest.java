package io.github.batnam.loyalty.bridge.webhook;

import io.github.batnam.loyalty.bridge.config.VoucherWebhookProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class HmacVerifierTest {

    private static final String SECRET = "test-secret";
    private final HmacVerifier verifier =
            new HmacVerifier(new VoucherWebhookProperties(SECRET, 300));

    private static String sign(long ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsValidSignatureWithinWindow() throws Exception {
        Instant now = Instant.parse("2026-05-29T12:00:00Z");
        long ts = now.getEpochSecond();
        String body = "{\"jobHandle\":\"J1\",\"status\":\"READY\"}";

        assertThat(verifier.verify(sign(ts, body), ts, body, now)).isTrue();
    }

    @Test
    void rejectsTamperedBody() throws Exception {
        Instant now = Instant.parse("2026-05-29T12:00:00Z");
        long ts = now.getEpochSecond();
        String sig = sign(ts, "{\"jobHandle\":\"J1\",\"status\":\"READY\"}");

        assertThat(verifier.verify(sig, ts, "{\"jobHandle\":\"J1\",\"status\":\"FAILED\"}", now)).isFalse();
    }

    @Test
    void rejectsStaleTimestamp() throws Exception {
        Instant now = Instant.parse("2026-05-29T12:00:00Z");
        long ts = now.minusSeconds(301).getEpochSecond();   // just outside the 300s window
        String body = "{\"jobHandle\":\"J1\",\"status\":\"READY\"}";

        assertThat(verifier.verify(sign(ts, body), ts, body, now)).isFalse();
    }
}
