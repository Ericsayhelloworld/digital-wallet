package com.example.wallet.operation;

import com.example.wallet.common.CurrentUser;
import com.example.wallet.common.Ids;
import com.example.wallet.common.Pagination;
import com.example.wallet.common.Pagination.Page;
import com.example.wallet.common.Time;
import com.example.wallet.config.Limits;
import com.example.wallet.error.ApiException;
import com.example.wallet.ledger.LedgerService;
import com.example.wallet.ledger.LedgerService.Leg;
import com.example.wallet.outbox.OutboxService;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.example.wallet.ledger.LedgerService.EXTERNAL_BANK;
import static com.example.wallet.ledger.LedgerService.PAYOUT_HOLD;

@Service
public class OperationService {

    private static final Logger log = LoggerFactory.getLogger(OperationService.class);
    private static final Set<String> STATUSES = Set.of("PENDING", "SUCCEEDED", "FAILED");

    private final OperationRepository operations;
    private final WalletService walletService;
    private final WalletRepository wallets;
    private final LedgerService ledger;
    private final BankSubmitter bankSubmitter;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Limits limits;
    private final OutboxService outbox;

    public OperationService(OperationRepository operations, WalletService walletService, WalletRepository wallets,
                            LedgerService ledger, BankSubmitter bankSubmitter, JdbcClient jdbc,
                            TransactionTemplate tx, Limits limits, OutboxService outbox) {
        this.operations = operations;
        this.walletService = walletService;
        this.wallets = wallets;
        this.ledger = ledger;
        this.bankSubmitter = bankSubmitter;
        this.jdbc = jdbc;
        this.tx = tx;
        this.limits = limits;
        this.outbox = outbox;
    }

    /** DEP-1 and DEP-2: PENDING, no ledger entry yet; the CHARGE goes to the bank after the commit. */
    public Operation.Response createDeposit(CurrentUser user, long amount) {
        limits.checkAmount(amount);
        Wallet wallet = walletService.requireWallet(user.id());
        UUID id = UUID.randomUUID();
        operations.insertPending(OperationKind.DEPOSIT, id, wallet.id(), amount, Time.now());
        bankSubmitter.submitAfterCommit(OperationKind.DEPOSIT, id, amount);
        log.info("Deposit {} PENDING: {} cents to {}", id, amount, wallet.id());
        return operations.findById(OperationKind.DEPOSIT, id).orElseThrow().toResponse();
    }

    /** WDR-1 and WDR-2: reserve the amount (wallet -> PAYOUT_HOLD) and create the withdrawal in one transaction. */
    public Operation.Response createWithdrawal(CurrentUser user, long amount) {
        limits.checkAmount(amount);
        Wallet wallet = walletService.requireWallet(user.id());

        // CON-2: lock the wallet so that the balance check and the balance change are atomic
        long balance = jdbc.sql("SELECT balance FROM accounts WHERE id = :id FOR UPDATE")
                .param("id", wallet.id()).query(Long.class).single();

        long sentToday = walletService.sentToday(wallet.id());
        if (sentToday + amount > limits.dailyLimit()) {
            throw ApiException.unprocessable("DAILY_LIMIT_EXCEEDED", "Daily limit exceeded",
                    "The wallet has sent " + sentToday + " of " + limits.dailyLimit() + " cents today.");
        }
        if (balance < amount) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Insufficient funds",
                    "The wallet holds " + balance + " cents and the withdrawal needs " + amount + ".");
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime now = Time.now();
        operations.insertPending(OperationKind.WITHDRAWAL, id, wallet.id(), amount, now);
        // LED-3: wallet -x, PAYOUT_HOLD +x
        ledger.post("WITHDRAWAL", id, now, List.of(
                new Leg(wallet.id(), "WITHDRAWAL", -amount, null, null),
                new Leg(PAYOUT_HOLD, "WITHDRAWAL", amount, null, null)));
        outbox.add("withdrawal.requested", id, eventPayload(OperationKind.WITHDRAWAL, id, wallet, amount, null, false));
        bankSubmitter.submitAfterCommit(OperationKind.WITHDRAWAL, id, amount);
        log.info("Withdrawal {} PENDING: {} cents from {}", id, amount, wallet.id());
        return operations.findById(OperationKind.WITHDRAWAL, id).orElseThrow().toResponse();
    }

    /**
     * DEP-3, WDR-3, RES-6: applies the result of the bank exactly once, whether it comes from a webhook,
     * from several copies of it, or from the reconciliation job.
     */
    public void applyResult(OperationKind kind, UUID id, String bankStatus, String reason) {
        tx.executeWithoutResult(status -> {
            Operation op = operations.findByIdForUpdate(kind, id).orElseThrow(ApiException::notFound);
            if (!"PENDING".equals(op.status())) {
                return; // RES-9: already final, nothing changes
            }
            if (!"SUCCEEDED".equals(bankStatus) && !"FAILED".equals(bankStatus)) {
                return; // RES-5: only a final bank status finishes an operation
            }
            OffsetDateTime now = Time.now();
            long x = op.amount();
            String failureReason = null;

            if (kind == OperationKind.DEPOSIT && "SUCCEEDED".equals(bankStatus)) {
                ledger.post("DEPOSIT", id, now, List.of(
                        new Leg(EXTERNAL_BANK, "DEPOSIT", -x, null, null),
                        new Leg(op.walletId(), "DEPOSIT", x, null, null)));
            } else if (kind == OperationKind.WITHDRAWAL && "SUCCEEDED".equals(bankStatus)) {
                ledger.post("WITHDRAWAL", id, now, List.of(
                        new Leg(PAYOUT_HOLD, "PAYOUT", -x, null, null),
                        new Leg(EXTERNAL_BANK, "PAYOUT", x, null, null)));
            } else if (kind == OperationKind.WITHDRAWAL) { // FAILED: the money goes back to the wallet
                ledger.post("WITHDRAWAL", id, now, List.of(
                        new Leg(PAYOUT_HOLD, "WITHDRAWAL_REVERSAL", -x, null, null),
                        new Leg(op.walletId(), "WITHDRAWAL_REVERSAL", x, null, null)));
            }
            // a failed deposit posts nothing
            if ("FAILED".equals(bankStatus)) {
                failureReason = reason == null ? "UNKNOWN" : reason;
            }
            operations.complete(kind, id, bankStatus, failureReason, now);

            // deposit.succeeded, deposit.failed, withdrawal.succeeded, withdrawal.failed
            Wallet wallet = wallets.findById(op.walletId()).orElseThrow();
            String eventType = (kind == OperationKind.DEPOSIT ? "deposit." : "withdrawal.") + bankStatus.toLowerCase();
            outbox.add(eventType, id, eventPayload(kind, id, wallet, x, failureReason, true));
            log.info("{} {} {}{}", kind, id, bankStatus, failureReason == null ? "" : " (" + failureReason + ")");
        });
    }

    /** API-4: an operation of another user is answered exactly like one that does not exist. */
    public Operation.Response get(CurrentUser user, OperationKind kind, String id) {
        Wallet wallet = wallets.findByUserId(user.id()).orElseThrow(ApiException::notFound);
        UUID uuid = Ids.parse(id).orElseThrow(ApiException::notFound);
        return operations.findByIdAndWallet(kind, uuid, wallet.id()).orElseThrow(ApiException::notFound).toResponse();
    }

    public Page<Operation.Response> list(CurrentUser user, OperationKind kind, String status, Integer limit, String cursor) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.validation("status", "must be one of " + STATUSES);
        }
        int pageSize = Pagination.limit(limit);
        Long position = Pagination.decode(cursor);
        var wallet = wallets.findByUserId(user.id());
        if (wallet.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        List<Operation> rows = operations.list(kind, wallet.get().id(), status, pageSize + 1, position);
        boolean more = rows.size() > pageSize;
        List<Operation> page = more ? rows.subList(0, pageSize) : rows;
        String next = more ? Pagination.encode(page.get(page.size() - 1).seq()) : null;
        return new Page<>(page.stream().map(Operation::toResponse).toList(), next);
    }

    /** Section 7: payload of the deposit and withdrawal events. */
    private static Map<String, Object> eventPayload(OperationKind kind, UUID id, Wallet wallet, long amount,
                                                    String failureReason, boolean withFailureReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(kind == OperationKind.DEPOSIT ? "depositId" : "withdrawalId", id.toString());
        payload.put("walletId", wallet.id().toString());
        payload.put("userId", wallet.userId().toString());
        payload.put("amount", amount);
        payload.put("currency", "USD");
        if (withFailureReason) {
            payload.put("failureReason", failureReason);
        }
        return payload;
    }
}
