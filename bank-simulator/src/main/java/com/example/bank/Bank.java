package com.example.bank;

import com.example.bank.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** The bank itself: transactions in memory, settled after settleDelayMs. */
@Service
public class Bank {

    public record Created(BankTransaction transaction, boolean isNew) {
    }

    private final Map<String, BankTransaction> byReference = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final WebhookSender webhooks;
    private volatile FailureSettings settings = FailureSettings.defaults();

    public Bank(WebhookSender webhooks) {
        this.webhooks = webhooks;
    }

    /** BNK-1 and BNK-2: idempotent by reference. */
    public synchronized Created create(String reference, String type, long amount) {
        BankTransaction existing = byReference.get(reference);
        if (existing != null) {
            if (existing.type().equals(type) && existing.amount() == amount) {
                return new Created(existing, false);
            }
            throw new ApiException(HttpStatus.CONFLICT, "REFERENCE_CONFLICT", "Reference conflict",
                    "The reference is known with a different type or amount.");
        }
        String id = String.format("bk-%06d", counter.incrementAndGet());
        BankTransaction created = new BankTransaction(id, reference, type, amount, "PENDING", null);
        byReference.put(reference, created);
        scheduler.schedule(() -> settle(reference), settings.settleDelayMs(), TimeUnit.MILLISECONDS);
        return new Created(created, true);
    }

    public Optional<BankTransaction> find(String reference) {
        return Optional.ofNullable(byReference.get(reference));
    }

    public List<BankTransaction> all() {
        return byReference.values().stream()
                .sorted(Comparator.comparing(BankTransaction::bankTransactionId).reversed())
                .toList();
    }

    public FailureSettings settings() {
        return settings;
    }

    public void updateSettings(FailureSettings newSettings) {
        this.settings = newSettings;
    }

    public synchronized void reset() {
        byReference.clear();
        settings = FailureSettings.defaults();
    }

    private void settle(String reference) {
        BankTransaction pending = byReference.get(reference);
        if (pending == null) {
            return; // deleted by reset
        }
        boolean decline = ThreadLocalRandom.current().nextDouble() < settings.declineRate();
        BankTransaction settled = new BankTransaction(pending.bankTransactionId(), pending.reference(),
                pending.type(), pending.amount(), decline ? "FAILED" : "SUCCEEDED", decline ? "DECLINED" : null);
        byReference.put(reference, settled);
        webhooks.send(settled, settings.webhookCopies());
    }
}
