package com.example.bank;

import com.example.bank.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class BankController {

    public record CreateRequest(@NotBlank @Size(max = 100) String reference,
                                @NotNull @Pattern(regexp = "CHARGE|PAYOUT") String type,
                                @NotNull @Positive Long amount) {
    }

    private final Bank bank;
    private final byte[] apiKey;

    public BankController(Bank bank, @Value("${app.api-key}") String apiKey) {
        this.bank = bank;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    // ---------- /bank/v1: used by wallet-service ----------

    @PostMapping("/bank/v1/transactions")
    public ResponseEntity<BankTransaction> create(@RequestHeader(value = "X-Api-Key", required = false) String key,
                                                  @Valid @RequestBody CreateRequest request) throws InterruptedException {
        FailureSettings s = before(key);
        Bank.Created result = bank.create(request.reference(), request.type(), request.amount());
        if (result.isNew() && ThreadLocalRandom.current().nextDouble() < s.hangRate()) {
            Thread.sleep(30_000); // "hang": created and settles, but the answer comes 30 seconds late
        }
        return ResponseEntity.status(result.isNew() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.transaction());
    }

    @GetMapping("/bank/v1/transactions/{reference}")
    public BankTransaction get(@RequestHeader(value = "X-Api-Key", required = false) String key,
                               @PathVariable String reference) throws InterruptedException {
        before(key);
        return bank.find(reference).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Not found", "The bank has no transaction with this reference."));
    }

    /** "down", the API key (BNK-5) and the response delay apply to every /bank/v1 request. */
    private FailureSettings before(String key) throws InterruptedException {
        FailureSettings s = bank.settings();
        if (s.down()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BANK_UNAVAILABLE", "Bank unavailable",
                    "The bank is down.");
        }
        if (key == null || !MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Unauthenticated",
                    "A valid X-Api-Key header is required.");
        }
        if (s.responseDelayMs() > 0) {
            Thread.sleep(s.responseDelayMs());
        }
        return s;
    }

    // ---------- /bank/admin: failure settings (BNK-6) ----------

    @GetMapping("/bank/admin/config")
    public FailureSettings getConfig() {
        return bank.settings();
    }

    @PutMapping("/bank/admin/config")
    public FailureSettings putConfig(@RequestBody FailureSettings settings) {
        FailureSettings complete = settings.withDefaults();
        bank.updateSettings(complete);
        return complete;
    }

    @GetMapping("/bank/admin/transactions")
    public List<BankTransaction> transactions() {
        return bank.all();
    }

    @PostMapping("/bank/admin/reset")
    public ResponseEntity<Void> reset() {
        bank.reset();
        return ResponseEntity.noContent().build();
    }
}
