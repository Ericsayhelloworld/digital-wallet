package com.example.wallet.wallet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private static final String SELECT = """
            SELECT w.id, w.user_id, w.username, w.display_name, a.balance, w.created_at
            FROM wallets w JOIN accounts a ON a.id = w.id
            """;

    private final JdbcClient jdbc;

    public WalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Wallet> findByUserId(UUID userId) {
        return jdbc.sql(SELECT + " WHERE w.user_id = :userId").param("userId", userId).query(this::map).optional();
    }

    public Optional<Wallet> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE w.id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<Wallet> findByUsername(String username) {
        return jdbc.sql(SELECT + " WHERE w.username = :username").param("username", username)
                .query(this::map).optional();
    }

    /** The wallet ID is also the ID of its ledger account. */
    public void insert(UUID id, UUID userId, String username, String displayName, OffsetDateTime createdAt) {
        jdbc.sql("INSERT INTO accounts (id, type, balance) VALUES (:id, 'WALLET', 0)")
                .param("id", id).update();
        jdbc.sql("""
                INSERT INTO wallets (id, user_id, username, display_name, created_at)
                VALUES (:id, :userId, :username, :displayName, :createdAt)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("username", username)
                .param("displayName", displayName)
                .param("createdAt", createdAt)
                .update();
    }

    /** MNY-3: transfers sent today plus withdrawals requested today; a failed withdrawal no longer counts. */
    public long sentToday(UUID walletId, OffsetDateTime startOfDay) {
        return jdbc.sql("""
                SELECT
                  (SELECT COALESCE(SUM(amount), 0) FROM transfers
                    WHERE from_wallet_id = :walletId AND created_at >= :startOfDay)
                + (SELECT COALESCE(SUM(amount), 0) FROM withdrawals
                    WHERE wallet_id = :walletId AND created_at >= :startOfDay AND status <> 'FAILED')
                """)
                .param("walletId", walletId)
                .param("startOfDay", startOfDay)
                .query(Long.class).single();
    }

    /** WAL-3: the amount reserved by pending withdrawals. */
    public long reserved(UUID walletId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM withdrawals WHERE wallet_id = :walletId AND status = 'PENDING'")
                .param("walletId", walletId)
                .query(Long.class).single();
    }

    private Wallet map(ResultSet rs, int row) throws SQLException {
        return new Wallet(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getLong("balance"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
