package com.example.wallet.operation;

import com.example.wallet.bank.BankClient;
import com.example.wallet.bank.BankTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends deposits and withdrawals to the bank.
 * CON-5: never inside a database transaction. RES-1: never while the customer waits for the response.
 */
@Component
public class BankSubmitter {

    private static final Logger log = LoggerFactory.getLogger(BankSubmitter.class);

    private final BankClient bank;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public BankSubmitter(BankClient bank) {
        this.bank = bank;
    }

    /** Called inside the transaction that creates the operation; the bank is called only after it commits. */
    public void submitAfterCommit(OperationKind kind, UUID id, long amount) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.submit(() -> submit(kind, id, amount));
            }
        });
    }

    /** RES-4: if the call fails we do not know the outcome; the operation stays PENDING for reconciliation. */
    public void submit(OperationKind kind, UUID id, long amount) {
        try {
            BankTransaction result = bank.submit(id.toString(), kind.bankType, amount);
            log.info("Submitted {} {} to the bank: {} {}", kind, id, result.bankTransactionId(), result.status());
        } catch (Exception e) {
            log.warn("Bank call for {} {} failed ({}); reconciliation will check it later", kind, id, e.getMessage());
        }
    }
}
