package com.example.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/** BNK-4: sends the settled transaction to wallet-service, webhookCopies times, and never retries. */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final RestClient rest;
    private final ObjectMapper objectMapper;
    private final String url;
    private final byte[] secret;

    public WebhookSender(RestClient.Builder builder, ObjectMapper objectMapper,
                         @Value("${app.webhook-url}") String url,
                         @Value("${app.webhook-secret}") String secret) {
        this.rest = builder.build();
        this.objectMapper = objectMapper;
        this.url = url;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public void send(BankTransaction transaction, int copies) {
        String body;
        try {
            body = objectMapper.writeValueAsString(transaction);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (int i = 1; i <= copies; i++) {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            try {
                rest.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Bank-Timestamp", timestamp)
                        .header("X-Bank-Signature", sign(timestamp + "." + body))
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("Webhook {}/{} for {} ({}) delivered", i, copies, transaction.reference(), transaction.status());
            } catch (Exception e) {
                log.warn("Webhook {}/{} for {} failed: {}", i, copies, transaction.reference(), e.getMessage());
            }
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
}
