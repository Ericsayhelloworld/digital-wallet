package com.example.wallet.operation;

import com.example.wallet.bank.BankClient;
import com.example.wallet.bank.BankTransaction;
import com.example.wallet.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RES-7: every operation that has been PENDING for more than 30 seconds is checked with the bank.
 * If the bank knows it and it is final, the result is applied; if the bank never got it, it is sent again.
 * This also finishes operations after wallet-service was restarted (RES-8).
 * ADM-4: an administrator can start a run at once, for every PENDING operation whatever its age.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final OperationRepository operations;
    private final OperationService service;
    private final BankClient bank;
    private final BankSubmitter submitter;
    private final long pendingAfterSeconds;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public Reconciler(OperationRepository operations, OperationService service, BankClient bank,
                      BankSubmitter submitter,
                      @Value("${app.reconciliation.pending-after-seconds}") long pendingAfterSeconds) {
        this.operations = operations;
        this.service = service;
        this.bank = bank;
        this.submitter = submitter;
        this.pendingAfterSeconds = pendingAfterSeconds;
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.interval-ms}")
    public void run() {
        reconcileCreatedBefore(Time.now().minusSeconds(pendingAfterSeconds));
    }

    /** ADM-4: starts a run in the background and returns at once. */
    public void startNow() {
        executor.submit(() -> {
            log.info("Reconciliation started by an administrator");
            reconcileCreatedBefore(Time.now().plusSeconds(1));
        });
    }

    private void reconcileCreatedBefore(OffsetDateTime before) {
        for (OperationKind kind : OperationKind.values()) {
            for (Operation op : operations.findPendingCreatedBefore(kind, before)) {
                reconcile(op);
            }
        }
    }

    private void reconcile(Operation op) {
        try {
            Optional<BankTransaction> transaction = bank.find(op.id().toString());
            if (transaction.isEmpty()) {
                log.info("Reconciliation: the bank does not know {} {}; submitting it again", op.kind(), op.id());
                submitter.submit(op.kind(), op.id(), op.amount());
            } else if (transaction.get().isFinal()) {
                log.info("Reconciliation: applying {} for {} {}", transaction.get().status(), op.kind(), op.id());
                service.applyResult(op.kind(), op.id(), transaction.get().status(), transaction.get().reason());
            }
        } catch (Exception e) {
            log.warn("Reconciliation of {} {} failed: {}", op.kind(), op.id(), e.getMessage());
        }
    }
}
