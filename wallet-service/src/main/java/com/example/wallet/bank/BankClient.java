package com.example.wallet.bank;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/** REST calls to bank-simulator, authenticated with X-Api-Key (SEC-4), with a response timeout (RES-3). */
@Component
public class BankClient {

    private final RestClient rest;

    public BankClient(RestClient.Builder builder,
                      @Value("${app.bank.base-url}") String baseUrl,
                      @Value("${app.bank.api-key}") String apiKey,
                      @Value("${app.bank.timeout-ms}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.rest = builder
                .baseUrl(baseUrl)
                .defaultHeader("X-Api-Key", apiKey)
                .requestFactory(factory)
                .build();
    }

    /** POST /bank/v1/transactions. The reference is the ID of the deposit or withdrawal (RES-2). */
    public BankTransaction submit(String reference, String type, long amount) {
        return rest.post()
                .uri("/bank/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reference", reference, "type", type, "amount", amount))
                .retrieve()
                .body(BankTransaction.class);
    }

    /** GET /bank/v1/transactions/{reference}; empty if the bank never created it. */
    public Optional<BankTransaction> find(String reference) {
        try {
            return Optional.ofNullable(rest.get()
                    .uri("/bank/v1/transactions/{reference}", reference)
                    .retrieve()
                    .body(BankTransaction.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
