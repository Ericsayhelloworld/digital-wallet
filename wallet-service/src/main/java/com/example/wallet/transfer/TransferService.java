package com.example.wallet.transfer;

import com.example.wallet.common.CurrentUser;
import com.example.wallet.common.Ids;
import com.example.wallet.common.Time;
import com.example.wallet.config.Limits;
import com.example.wallet.error.ApiException;
import com.example.wallet.ledger.LedgerService;
import com.example.wallet.ledger.LedgerService.Leg;
import com.example.wallet.outbox.OutboxService;
import com.example.wallet.wallet.Wallet;
import com.example.wallet.wallet.WalletRepository;
import com.example.wallet.wallet.WalletService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    public record TransferRequest(@NotBlank String toWalletId,
                                  @NotNull Long amount,
                                  @Size(max = 140) String note) {
    }

    public record TransferResponse(String id, String fromWalletId, String toWalletId, long amount, String currency,
                                   String note, String status, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final WalletService walletService;
    private final WalletRepository wallets;
    private final LedgerService ledger;
    private final Limits limits;
    private final OutboxService outbox;

    public TransferService(JdbcClient jdbc, WalletService walletService, WalletRepository wallets,
                           LedgerService ledger, Limits limits, OutboxService outbox) {
        this.jdbc = jdbc;
        this.walletService = walletService;
        this.wallets = wallets;
        this.ledger = ledger;
        this.limits = limits;
        this.outbox = outbox;
    }

    /**
     * TRF-1: must run inside one database transaction (the caller opens it).
     * Both entries, both balances and the transfer record are written together, or nothing is.
     */
    public TransferResponse create(CurrentUser user, TransferRequest request) {
        long amount = request.amount();
        limits.checkAmount(amount);

        Wallet from = walletService.requireWallet(user.id());
        Wallet to = Ids.parse(request.toWalletId()).flatMap(wallets::findById)
                .orElseThrow(() -> ApiException.unprocessable("RECIPIENT_NOT_FOUND", "Recipient not found",
                        "The recipient wallet does not exist."));
        if (from.id().equals(to.id())) {
            throw ApiException.unprocessable("SAME_WALLET", "Same wallet", "A wallet cannot send money to itself.");
        }

        // CON-2 / CON-3: lock both accounts, always in the same order (by ID), so that two transfers in
        // opposite directions cannot deadlock. Until we commit, nobody else can change these balances.
        Map<UUID, Long> balances = new HashMap<>();
        jdbc.sql("SELECT id, balance FROM accounts WHERE id IN (:a, :b) ORDER BY id FOR UPDATE")
                .param("a", from.id())
                .param("b", to.id())
                .query(rs -> {
                    balances.put(rs.getObject("id", UUID.class), rs.getLong("balance"));
                });
        long balance = balances.get(from.id());

        long sentToday = walletService.sentToday(from.id());
        if (sentToday + amount > limits.dailyLimit()) {
            throw ApiException.unprocessable("DAILY_LIMIT_EXCEEDED", "Daily limit exceeded",
                    "The wallet has sent " + sentToday + " of " + limits.dailyLimit() + " cents today.");
        }
        if (balance < amount) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Insufficient funds",
                    "The wallet holds " + balance + " cents and the transfer needs " + amount + ".");
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime now = Time.now();
        jdbc.sql("""
                INSERT INTO transfers (id, from_wallet_id, to_wallet_id, amount, note, status, created_at)
                VALUES (:id, :from, :to, :amount, :note, 'COMPLETED', :createdAt)
                """)
                .param("id", id)
                .param("from", from.id())
                .param("to", to.id())
                .param("amount", amount)
                .param("note", request.note(), Types.VARCHAR)
                .param("createdAt", now)
                .update();

        // LED-3: A -x, B +x
        ledger.post("TRANSFER", id, now, List.of(
                new Leg(from.id(), "TRANSFER_OUT", -amount, to.id(), request.note()),
                new Leg(to.id(), "TRANSFER_IN", amount, from.id(), request.note())));

        // Section 7: transfer.completed, in the same transaction (EVT-1)
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", id.toString());
        payload.put("amount", amount);
        payload.put("currency", "USD");
        payload.put("note", request.note());
        payload.put("from", OutboxService.party(from));
        payload.put("to", OutboxService.party(to));
        outbox.add("transfer.completed", id, payload);

        log.info("Transfer {} COMPLETED: {} cents from {} to {}", id, amount, from.id(), to.id());
        return new TransferResponse(id.toString(), from.id().toString(), to.id().toString(), amount, "USD",
                request.note(), "COMPLETED", now);
    }

    /** TRF-4 and API-4: only the sender and the receiver see a transfer; everyone else gets 404. */
    public TransferResponse get(CurrentUser user, String transferId) {
        Wallet wallet = wallets.findByUserId(user.id()).orElseThrow(ApiException::notFound);
        UUID id = Ids.parse(transferId).orElseThrow(ApiException::notFound);
        return jdbc.sql("""
                SELECT id, from_wallet_id, to_wallet_id, amount, note, status, created_at FROM transfers
                WHERE id = :id AND (from_wallet_id = :wallet OR to_wallet_id = :wallet)
                """)
                .param("id", id)
                .param("wallet", wallet.id())
                .query((rs, row) -> new TransferResponse(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getObject("from_wallet_id", UUID.class).toString(),
                        rs.getObject("to_wallet_id", UUID.class).toString(),
                        rs.getLong("amount"),
                        "USD",
                        rs.getString("note"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(ApiException::notFound);
    }
}
