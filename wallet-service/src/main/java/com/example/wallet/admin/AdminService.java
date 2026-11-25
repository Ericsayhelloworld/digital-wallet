package com.example.wallet.admin;

import com.example.wallet.bank.BankClient;
import com.example.wallet.common.Pagination;
import com.example.wallet.common.Pagination.Page;
import com.example.wallet.common.Time;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Section 5.6: the administration API of wallet-service. */
@Service
public class AdminService {

    public record Check(String id, boolean ok, String detail) {
    }

    public record Totals(long wallets, long payoutHold, long externalBank) {
    }

    public record IntegrityReport(OffsetDateTime checkedAt, boolean ok, List<Check> checks, Totals totals) {
    }

    public record Status(long pendingDeposits, long pendingWithdrawals, long oldestPendingSeconds,
                         long outboxUnsent, long outboxOldestSeconds, String bankCircuit) {
    }

    public record WalletRow(String walletId, String username, String displayName, long balance, long reserved) {
    }

    private record Row(long seq, WalletRow wallet) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;
    private final BankClient bank;

    public AdminService(JdbcClient jdbc, PlatformTransactionManager transactionManager, BankClient bank) {
        this.jdbc = jdbc;
        this.bank = bank;
        // ADM-1: all checks read ONE consistent snapshot. In PostgreSQL, REPEATABLE READ means every query of
        // the transaction sees the database as it was at the first query, even while other requests commit.
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    // ---------------------------------------------------------------- ADM-1

    public IntegrityReport integrity() {
        return snapshot.execute(status -> checkInvariants());
    }

    private IntegrityReport checkInvariants() {
        OffsetDateTime checkedAt = Time.now();

        // INV1: all entries add up to zero, and so does every single posting
        Map<String, Object> ledger = jdbc.sql("""
                SELECT COUNT(*) AS entries, COUNT(DISTINCT posting_id) AS postings,
                       COALESCE(SUM(amount), 0) AS total
                FROM ledger_entries
                """).query().singleRow();
        long unbalanced = number(jdbc.sql("""
                SELECT COUNT(*) FROM (SELECT posting_id FROM ledger_entries
                                      GROUP BY posting_id HAVING SUM(amount) <> 0) t
                """).query().singleValue());
        long total = number(ledger.get("total"));
        Check inv1 = new Check("INV-1", total == 0 && unbalanced == 0,
                number(ledger.get("entries")) + " entries in " + number(ledger.get("postings")) + " postings, sum "
                        + total + (unbalanced > 0 ? ", " + unbalanced + " postings do not add up to zero" : ""));

        // INV2: the balance of every account equals the sum of its entries
        Map<String, Object> accounts = jdbc.sql("""
                SELECT COUNT(*) AS accounts,
                       COUNT(*) FILTER (WHERE a.balance <> COALESCE(e.total, 0)) AS mismatched
                FROM accounts a
                LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM ledger_entries GROUP BY account_id) e
                       ON e.account_id = a.id
                """).query().singleRow();
        long mismatched = number(accounts.get("mismatched"));
        Check inv2 = new Check("INV-2", mismatched == 0, number(accounts.get("accounts")) + " accounts checked"
                + (mismatched > 0 ? ", " + mismatched + " do not match their entries" : ""));

        // the three balances that the other checks need
        Map<String, Object> balances = jdbc.sql("""
                SELECT MIN(balance) FILTER (WHERE type = 'WALLET')          AS lowest,
                       COALESCE(SUM(balance) FILTER (WHERE type = 'WALLET'), 0) AS wallets,
                       MAX(balance) FILTER (WHERE type = 'PAYOUT_HOLD')     AS hold,
                       MAX(balance) FILTER (WHERE type = 'EXTERNAL_BANK')   AS external
                FROM accounts
                """).query().singleRow();
        Object lowestValue = balances.get("lowest");
        long hold = number(balances.get("hold"));
        long external = number(balances.get("external"));

        // INV3: no wallet balance and not the hold is negative
        boolean noNegativeWallet = lowestValue == null || number(lowestValue) >= 0;
        Check inv3 = new Check("INV-3", noNegativeWallet && hold >= 0,
                lowestValue == null ? "no wallets yet" : "lowest wallet balance " + number(lowestValue));

        // INV4: the hold equals the sum of all PENDING withdrawals
        long pendingWithdrawals = number(jdbc.sql(
                "SELECT COALESCE(SUM(amount), 0) FROM withdrawals WHERE status = 'PENDING'").query().singleValue());
        Check inv4 = new Check("INV-4", hold == pendingWithdrawals,
                "hold " + hold + ", pending withdrawals " + pendingWithdrawals);

        // INV5: EXTERNAL_BANK = succeeded withdrawals - succeeded deposits
        long deposits = number(jdbc.sql(
                "SELECT COALESCE(SUM(amount), 0) FROM deposits WHERE status = 'SUCCEEDED'").query().singleValue());
        long withdrawals = number(jdbc.sql(
                "SELECT COALESCE(SUM(amount), 0) FROM withdrawals WHERE status = 'SUCCEEDED'").query().singleValue());
        Check inv5 = new Check("INV-5", external == withdrawals - deposits,
                "external " + external + ", deposits " + deposits + ", withdrawals " + withdrawals);

        // INV6: in posting order, every balanceAfter = previous balanceAfter + amount (per wallet)
        Map<String, Object> chain = jdbc.sql("""
                SELECT COUNT(*) AS entries, COUNT(*) FILTER (WHERE broken) AS broken
                FROM (SELECT e.balance_after <> COALESCE(LAG(e.balance_after)
                                 OVER (PARTITION BY e.account_id ORDER BY e.seq), 0) + e.amount AS broken
                      FROM ledger_entries e JOIN accounts a ON a.id = e.account_id
                      WHERE a.type = 'WALLET') t
                """).query().singleRow();
        long broken = number(chain.get("broken"));
        Check inv6 = new Check("INV-6", broken == 0, number(chain.get("entries")) + " wallet entries checked"
                + (broken > 0 ? ", " + broken + " do not follow the previous balance" : ""));

        List<Check> checks = List.of(inv1, inv2, inv3, inv4, inv5, inv6);
        boolean ok = checks.stream().allMatch(Check::ok);
        return new IntegrityReport(checkedAt, ok, checks,
                new Totals(number(balances.get("wallets")), hold, external));
    }

    // ---------------------------------------------------------------- ADM-2

    public Status status() {
        OffsetDateTime now = Time.now();
        return jdbc.sql("""
                SELECT
                  (SELECT COUNT(*) FROM deposits WHERE status = 'PENDING')    AS pending_deposits,
                  (SELECT COUNT(*) FROM withdrawals WHERE status = 'PENDING') AS pending_withdrawals,
                  (SELECT MIN(created_at) FROM (
                      SELECT created_at FROM deposits WHERE status = 'PENDING'
                      UNION ALL
                      SELECT created_at FROM withdrawals WHERE status = 'PENDING') p) AS oldest_pending,
                  (SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL)         AS outbox_unsent,
                  (SELECT MIN(created_at) FROM outbox WHERE sent_at IS NULL)  AS outbox_oldest
                """)
                .query((rs, row) -> new Status(
                        rs.getLong("pending_deposits"),
                        rs.getLong("pending_withdrawals"),
                        secondsSince(rs.getObject("oldest_pending", OffsetDateTime.class), now),
                        rs.getLong("outbox_unsent"),
                        secondsSince(rs.getObject("outbox_oldest", OffsetDateTime.class), now),
                        bank.circuitState()))
                .single();
    }

    // ---------------------------------------------------------------- ADM-3

    public Page<WalletRow> wallets(int limit, Long cursor) {
        StringBuilder sql = new StringBuilder("""
                SELECT w.id, w.seq, w.username, w.display_name, a.balance,
                       (SELECT COALESCE(SUM(x.amount), 0) FROM withdrawals x
                        WHERE x.wallet_id = w.id AND x.status = 'PENDING') AS reserved
                FROM wallets w JOIN accounts a ON a.id = w.id
                """);
        Map<String, Object> params = new HashMap<>();
        if (cursor != null) {
            sql.append(" WHERE w.seq < :cursor");
            params.put("cursor", cursor);
        }
        sql.append(" ORDER BY w.seq DESC LIMIT :limit");
        params.put("limit", limit + 1);
        List<Row> rows = jdbc.sql(sql.toString()).params(params).query((rs, i) -> new Row(rs.getLong("seq"),
                new WalletRow(rs.getObject("id", UUID.class).toString(), rs.getString("username"),
                        rs.getString("display_name"), rs.getLong("balance"), rs.getLong("reserved")))).list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        String next = more ? Pagination.encode(page.get(page.size() - 1).seq()) : null;
        return new Page<>(page.stream().map(Row::wallet).toList(), next);
    }

    // ---------------------------------------------------------------- helpers

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static long secondsSince(OffsetDateTime time, OffsetDateTime now) {
        return time == null ? 0 : Math.max(0, Duration.between(time, now).getSeconds());
    }
}
