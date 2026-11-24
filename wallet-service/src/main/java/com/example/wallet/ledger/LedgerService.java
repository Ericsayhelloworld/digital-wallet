package com.example.wallet.ledger;

import com.example.wallet.common.Pagination;
import com.example.wallet.common.Pagination.Page;
import com.example.wallet.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only place where balances change (LED-1). Every business event is posted as a group of
 * entries that add up to zero (LED-3); every entry stores the balance after it (LED-4).
 */
@Service
public class LedgerService {

    public static final UUID EXTERNAL_BANK = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID PAYOUT_HOLD = UUID.fromString("00000000-0000-0000-0000-000000000002");

    public static final Set<String> ENTRY_TYPES =
            Set.of("DEPOSIT", "TRANSFER_OUT", "TRANSFER_IN", "WITHDRAWAL", "WITHDRAWAL_REVERSAL");

    /** One line of a posting. */
    public record Leg(UUID accountId, String type, long amount, UUID counterpartyWalletId, String note) {
    }

    public record Counterparty(String walletId, String displayName) {
    }

    public record EntryResponse(String id, String type, long amount, long balanceAfter, Counterparty counterparty,
                                String note, String referenceType, String referenceId,
                                OffsetDateTime createdAt) {
    }

    private record Row(long seq, EntryResponse entry) {
    }

    private final JdbcClient jdbc;

    public LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the database transaction of the business operation. */
    public void post(String referenceType, UUID referenceId, OffsetDateTime at, List<Leg> legs) {
        long sum = legs.stream().mapToLong(Leg::amount).sum();
        if (sum != 0) {
            throw new IllegalStateException("A posting must add up to zero, got " + sum);
        }
        // Lock every account of this posting in ID order, so that two postings never wait for each other
        // in opposite order (no deadlocks, CON-3).
        List<UUID> accountIds = legs.stream().map(Leg::accountId).distinct().toList();
        jdbc.sql("SELECT id FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", accountIds)
                .query(UUID.class).list();

        UUID postingId = UUID.randomUUID();
        for (Leg leg : legs) {
            long balanceAfter = jdbc.sql("UPDATE accounts SET balance = balance + :amount WHERE id = :id RETURNING balance")
                    .param("amount", leg.amount())
                    .param("id", leg.accountId())
                    .query(Long.class).single();
            jdbc.sql("""
                    INSERT INTO ledger_entries (id, posting_id, account_id, type, amount, balance_after,
                        counterparty_wallet_id, note, reference_type, reference_id, created_at)
                    VALUES (:id, :postingId, :accountId, :type, :amount, :balanceAfter,
                        :counterparty, :note, :referenceType, :referenceId, :createdAt)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("postingId", postingId)
                    .param("accountId", leg.accountId())
                    .param("type", leg.type())
                    .param("amount", leg.amount())
                    .param("balanceAfter", balanceAfter)
                    .param("counterparty", leg.counterpartyWalletId(), Types.OTHER)
                    .param("note", leg.note(), Types.VARCHAR)
                    .param("referenceType", referenceType)
                    .param("referenceId", referenceId)
                    .param("createdAt", at)
                    .update();
        }
    }

    /** WAL-5: entries of one wallet, newest first, optionally filtered by type. */
    public Page<EntryResponse> statement(UUID walletId, String type, int limit, Long cursor) {
        if (type != null && !ENTRY_TYPES.contains(type)) {
            throw ApiException.validation("type", "must be one of " + ENTRY_TYPES);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.seq, e.type, e.amount, e.balance_after, e.counterparty_wallet_id,
                       cw.display_name AS counterparty_name, e.note, e.reference_type, e.reference_id, e.created_at
                FROM ledger_entries e
                LEFT JOIN wallets cw ON cw.id = e.counterparty_wallet_id
                WHERE e.account_id = :walletId
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("walletId", walletId);
        if (type != null) {
            sql.append(" AND e.type = :type");
            params.put("type", type);
        }
        if (cursor != null) {
            sql.append(" AND e.seq < :cursor");
            params.put("cursor", cursor);
        }
        sql.append(" ORDER BY e.seq DESC LIMIT :limit");
        params.put("limit", limit + 1); // one more row tells us whether there is a next page

        List<Row> rows = jdbc.sql(sql.toString()).params(params).query(this::map).list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        String nextCursor = more ? Pagination.encode(page.get(page.size() - 1).seq()) : null;
        return new Page<>(page.stream().map(Row::entry).toList(), nextCursor);
    }

    private Row map(ResultSet rs, int rowNum) throws SQLException {
        UUID counterpartyId = rs.getObject("counterparty_wallet_id", UUID.class);
        Counterparty counterparty = counterpartyId == null ? null
                : new Counterparty(counterpartyId.toString(), rs.getString("counterparty_name"));
        EntryResponse entry = new EntryResponse(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("type"),
                rs.getLong("amount"),
                rs.getLong("balance_after"),
                counterparty,
                rs.getString("note"),
                rs.getString("reference_type"),
                rs.getObject("reference_id", UUID.class).toString(),
                rs.getObject("created_at", OffsetDateTime.class));
        return new Row(rs.getLong("seq"), entry);
    }
}
