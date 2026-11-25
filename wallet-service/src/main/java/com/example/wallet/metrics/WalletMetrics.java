package com.example.wallet.metrics;

import com.example.wallet.admin.AdminService;
import com.example.wallet.admin.AdminService.Status;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * OPS-5: the business metrics of wallet-service.
 *   wallet_operations_total{type,result}  operations by type and result
 *   wallet_pending_operations{type}       number of pending deposits and withdrawals
 *   wallet_pending_oldest_seconds         age of the oldest pending operation
 *   wallet_outbox_unsent                  outbox backlog
 *   wallet_outbox_oldest_seconds          age of the oldest unsent event
 * (calls to the bank and the circuit state are published by BankClient)
 */
@Component
public class WalletMetrics {

    private static final Logger log = LoggerFactory.getLogger(WalletMetrics.class);

    private final MeterRegistry registry;
    private final AdminService admin;
    private final AtomicLong pendingDeposits = new AtomicLong();
    private final AtomicLong pendingWithdrawals = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final AtomicLong outboxUnsent = new AtomicLong();
    private final AtomicLong outboxOldestSeconds = new AtomicLong();

    public WalletMetrics(MeterRegistry registry, AdminService admin) {
        this.registry = registry;
        this.admin = admin;
        Gauge.builder("wallet.pending.operations", pendingDeposits, AtomicLong::get).tag("type", "deposit")
                .description("Deposits that are PENDING").register(registry);
        Gauge.builder("wallet.pending.operations", pendingWithdrawals, AtomicLong::get).tag("type", "withdrawal")
                .description("Withdrawals that are PENDING").register(registry);
        Gauge.builder("wallet.pending.oldest.seconds", oldestPendingSeconds, AtomicLong::get)
                .description("Age of the oldest PENDING operation").register(registry);
        Gauge.builder("wallet.outbox.unsent", outboxUnsent, AtomicLong::get)
                .description("Events in the outbox that are not yet on Kafka").register(registry);
        Gauge.builder("wallet.outbox.oldest.seconds", outboxOldestSeconds, AtomicLong::get)
                .description("Age of the oldest unsent outbox event").register(registry);
    }

    /** The gauges read the database every 5 seconds (not on every Prometheus scrape). */
    @Scheduled(fixedDelay = 5000)
    public void refresh() {
        try {
            Status s = admin.status();
            pendingDeposits.set(s.pendingDeposits());
            pendingWithdrawals.set(s.pendingWithdrawals());
            oldestPendingSeconds.set(s.oldestPendingSeconds());
            outboxUnsent.set(s.outboxUnsent());
            outboxOldestSeconds.set(s.outboxOldestSeconds());
        } catch (Exception e) {
            log.debug("Cannot refresh the metrics: {}", e.getMessage());
        }
    }

    /** Counts an operation; inside a transaction, only once it has committed. */
    public void operation(String type, String result) {
        Runnable count = () -> registry.counter("wallet.operations", "type", type, "result", result).increment();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    count.run();
                }
            });
        } else {
            count.run();
        }
    }
}
