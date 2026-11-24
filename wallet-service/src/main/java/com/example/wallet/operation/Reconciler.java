package com.example.wallet.operation;

import com.example.wallet.bank.BankClient;
import com.example.wallet.bank.BankTransaction;
import com.example.wallet.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * RES-7: every operation that has been PENDING for more than 30 seconds is checked with the bank.
 * If the bank knows it and it is final, the result is applied; if the bank never got it, it is sent again.
 * This also finishes operations after wallet-service was restarted (RES-8).
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final OperationRepository operations;
    private final OperationService service;
    private final BankClient bank;
    private final BankSubmitter submitter;
    private final long pendingAfterSeconds;

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
        for (OperationKind kind : OperationKind.values()) {
            for (Operation op : operations.findPendingCreatedBefore(kind, Time.now().minusSeconds(pendingAfterSeconds))) {
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
