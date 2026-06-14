package io.github.batnam.loyalty.bridge.webhook;

import io.github.batnam.loyalty.bridge.config.VoucherWebhookProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Verifies a voucher-webhook request (threat DD-2): the partner signs
 * {@code HMAC-SHA256(secret, "<timestamp>.<rawBody>")} and sends it hex-encoded in a header, plus
 * the timestamp. We reject stale timestamps (replay window) and mismatched signatures
 * (constant-time compare). Nonce-level dedup is deferred — it needs state, which the Bridge avoids
 *; the timestamp window is the stateless defence.
 */
@Component
public class HmacVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final VoucherWebhookProperties props;

    public HmacVerifier(VoucherWebhookProperties props) {
        this.props = props;
    }

    public boolean verify(String signatureHex, long timestamp, String rawBody, Instant now) {
        if (signatureHex == null || signatureHex.isBlank()) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > props.timestampToleranceSeconds()) {
            return false;   // outside the replay window
        }
        byte[] expected = hmac(timestamp + "." + rawBody);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHex.trim());
        } catch (IllegalArgumentException badHex) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);   // constant-time
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(props.hmacSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }
}
