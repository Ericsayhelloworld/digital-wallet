package com.example.wallet.bank;

import com.example.wallet.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Section 6.6 / RES-9: the signature is HMAC-SHA256 (lowercase hex) of "timestamp.body" with the shared
 * secret, and the timestamp must be at most 5 minutes old.
 */
@Component
public class WebhookSignature {

    private static final long MAX_AGE_SECONDS = 300;

    private final byte[] secret;

    public WebhookSignature(@Value("${app.bank.webhook-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String timestamp, String signature, String body) {
        if (timestamp == null || signature == null) {
            throw invalid();
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw invalid();
        }
        if (Math.abs(Instant.now().getEpochSecond() - sentAt) > MAX_AGE_SECONDS) {
            throw invalid();
        }
        byte[] expected = sign(timestamp + "." + body).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {   // constant-time comparison
            throw invalid();
        }
    }

    private String sign(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Invalid signature",
                "The webhook signature or timestamp is not valid.");
    }
}
