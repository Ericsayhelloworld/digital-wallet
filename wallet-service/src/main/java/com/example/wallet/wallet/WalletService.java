package com.example.wallet.wallet;

import com.example.wallet.common.CurrentUser;
import com.example.wallet.common.Time;
import com.example.wallet.config.Limits;
import com.example.wallet.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class WalletService {

    public record OpenResult(Wallet wallet, boolean created) {
    }

    public record WalletResponse(String id, String username, String displayName, String currency,
                                 long balance, long reserved, long dailyLimit, long sentToday,
                                 OffsetDateTime createdAt) {
    }

    private final WalletRepository wallets;
    private final TransactionTemplate tx;
    private final Limits limits;

    public WalletService(WalletRepository wallets, TransactionTemplate tx, Limits limits) {
        this.wallets = wallets;
        this.tx = tx;
        this.limits = limits;
    }

    /** WAL-1 and WAL-2: one wallet per user; username and name are copied from the token. */
    public OpenResult open(CurrentUser user) {
        var existing = wallets.findByUserId(user.id());
        if (existing.isPresent()) {
            return new OpenResult(existing.get(), false);
        }
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(status ->
                    wallets.insert(id, user.id(), user.username(), user.displayName(), Time.now()));
            return new OpenResult(wallets.findById(id).orElseThrow(), true);
        } catch (DuplicateKeyException e) {
            // two calls at the same moment: the database constraint lets only one of them create the wallet
            return new OpenResult(wallets.findByUserId(user.id()).orElseThrow(), false);
        }
    }

    public Wallet requireWallet(UUID userId) {
        return wallets.findByUserId(userId).orElseThrow(ApiException::walletNotFound);
    }

    public long sentToday(UUID walletId) {
        return wallets.sentToday(walletId, Time.startOfTodayUtc());
    }

    /** WAL-3 */
    public WalletResponse toResponse(Wallet wallet) {
        long reserved = wallets.reserved(wallet.id());
        return new WalletResponse(wallet.id().toString(), wallet.username(), wallet.displayName(), "USD",
                wallet.balance(), reserved, limits.dailyLimit(), sentToday(wallet.id()), wallet.createdAt());
    }
}
