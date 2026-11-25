package com.example.wallet.bank;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * REST calls to bank-simulator, authenticated with X-Api-Key (SEC-4).
 *
 * RES-3: every call has a response timeout, a bounded number of retries with exponential backoff and jitter,
 * and a circuit breaker. RES-4: a timeout, a connection error or a 5xx means "outcome unknown"; those are
 * retried with the SAME reference (the bank is idempotent by reference) and never treated as a failure.
 * A 404 or 409 is a clear answer from the bank and is neither retried nor counted against the bank.
 */
@Component
public class BankClient {

    private static final Logger log = LoggerFactory.getLogger(BankClient.class);

    private final RestClient rest;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public BankClient(RestClient.Builder builder, MeterRegistry registry,
                      @Value("${app.bank.base-url}") String baseUrl,
                      @Value("${app.bank.api-key}") String apiKey,
                      @Value("${app.bank.timeout-ms}") int timeoutMs,
                      @Value("${app.bank.retry.max-attempts:3}") int maxAttempts,
                      @Value("${app.bank.retry.initial-backoff-ms:200}") long initialBackoffMs,
                      @Value("${app.bank.circuit.failure-rate-percent:50}") float failureRatePercent,
                      @Value("${app.bank.circuit.open-seconds:15}") long openSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.rest = builder
                .baseUrl(baseUrl)
                .defaultHeader("X-Api-Key", apiKey)
                .requestFactory(factory)
                .build();

        // Circuit breaker: look at the last 10 calls; if at least half of them failed (and there were at
        // least 5), OPEN: calls are refused at once without reaching the bank. After openSeconds it becomes
        // HALF_OPEN and lets 2 test calls through; if they succeed it CLOSES again.
        this.circuitBreaker = CircuitBreaker.of("bank", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(failureRatePercent)
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(BankClient::outcomeUnknown)
                .build());
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Bank circuit breaker: {}", event.getStateTransition()))
                // OPS-5: wallet_bank_calls_total{outcome}: every single try, counted by the circuit breaker
                .onSuccess(event -> registry.counter("wallet.bank.calls", "outcome", "success").increment())
                .onError(event -> registry.counter("wallet.bank.calls", "outcome", "error").increment())
                .onCallNotPermitted(event -> registry.counter("wallet.bank.calls", "outcome", "circuit_open").increment());
        // OPS-5: wallet_bank_circuit_state: 0 = CLOSED, 1 = OPEN, 2 = HALF_OPEN
        Gauge.builder("wallet.bank.circuit.state", circuitBreaker, cb -> switch (cb.getState()) {
                    case CLOSED -> 0.0;
                    case OPEN -> 1.0;
                    case HALF_OPEN -> 2.0;
                    default -> 3.0;
                })
                .description("0 closed, 1 open, 2 half open")
                .register(registry);

        // Retry: at most maxAttempts tries, waiting 200 ms, then about 400 ms, ... with +/-50% random jitter.
        // A call refused by the open circuit is not retried.
        this.retry = Retry.of("bank", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(initialBackoffMs), 2.0, 0.5))
                .retryOnException(BankClient::outcomeUnknown)
                .build());
        this.retry.getEventPublisher().onRetry(event ->
                log.info("Bank call failed ({}); retry {} in {} ms", event.getLastThrowable().getClass().getSimpleName(),
                        event.getNumberOfRetryAttempts(), event.getWaitInterval().toMillis()));
    }

    /** POST /bank/v1/transactions. The reference is the ID of the deposit or withdrawal (RES-2). */
    public BankTransaction submit(String reference, String type, long amount) {
        return call(() -> rest.post()
                .uri("/bank/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reference", reference, "type", type, "amount", amount))
                .retrieve()
                .body(BankTransaction.class));
    }

    /** GET /bank/v1/transactions/{reference}; empty if the bank never created it. */
    public Optional<BankTransaction> find(String reference) {
        return call(() -> {
            try {
                return Optional.ofNullable(rest.get()
                        .uri("/bank/v1/transactions/{reference}", reference)
                        .retrieve()
                        .body(BankTransaction.class));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty(); // a clear answer, not a failure
            }
        });
    }

    /** ADM-2: CLOSED, OPEN or HALF_OPEN. */
    public String circuitState() {
        return circuitBreaker.getState().name();
    }

    /** The retry tries again; every single try goes through the circuit breaker. */
    private <T> T call(Supplier<T> request) {
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, request)).get();
    }

    /** RES-4: timeout or connection error (ResourceAccessException) and 5xx mean the outcome is unknown. */
    private static boolean outcomeUnknown(Throwable e) {
        return e instanceof ResourceAccessException || e instanceof HttpServerErrorException;
    }
}
