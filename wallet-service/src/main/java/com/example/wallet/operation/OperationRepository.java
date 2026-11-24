package com.example.wallet.operation;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OperationRepository {

    private static final String COLUMNS =
            "id, seq, wallet_id, amount, status, failure_reason, created_at, completed_at";

    private final JdbcClient jdbc;

    public OperationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPending(OperationKind kind, UUID id, UUID walletId, long amount, OffsetDateTime createdAt) {
        jdbc.sql("INSERT INTO " + kind.table + " (id, wallet_id, amount, status, created_at)"
                        + " VALUES (:id, :walletId, :amount, 'PENDING', :createdAt)")
                .param("id", id)
                .param("walletId", walletId)
                .param("amount", amount)
                .param("createdAt", createdAt)
                .update();
    }

    public Optional<Operation> findById(OperationKind kind, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + kind.table + " WHERE id = :id")
                .param("id", id).query(mapper(kind)).optional();
    }

    /** Locks the row until the transaction ends: two results for the same operation are applied one after the other. */
    public Optional<Operation> findByIdForUpdate(OperationKind kind, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + kind.table + " WHERE id = :id FOR UPDATE")
                .param("id", id).query(mapper(kind)).optional();
    }

    public Optional<Operation> findByIdAndWallet(OperationKind kind, UUID id, UUID walletId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + kind.table + " WHERE id = :id AND wallet_id = :walletId")
                .param("id", id).param("walletId", walletId).query(mapper(kind)).optional();
    }

    public List<Operation> list(OperationKind kind, UUID walletId, String status, int limit, Long cursor) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM " + kind.table + " WHERE wallet_id = :walletId");
        Map<String, Object> params = new HashMap<>();
        params.put("walletId", walletId);
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        if (cursor != null) {
            sql.append(" AND seq < :cursor");
            params.put("cursor", cursor);
        }
        sql.append(" ORDER BY seq DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(mapper(kind)).list();
    }

    public List<Operation> findPendingCreatedBefore(OperationKind kind, OffsetDateTime before) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + kind.table
                        + " WHERE status = 'PENDING' AND created_at < :before ORDER BY created_at")
                .param("before", before).query(mapper(kind)).list();
    }

    public void complete(OperationKind kind, UUID id, String status, String failureReason, OffsetDateTime at) {
        jdbc.sql("UPDATE " + kind.table + " SET status = :status, failure_reason = :reason, completed_at = :at"
                        + " WHERE id = :id AND status = 'PENDING'")
                .param("status", status)
                .param("reason", failureReason, Types.VARCHAR)
                .param("at", at)
                .param("id", id)
                .update();
    }

    private static RowMapper<Operation> mapper(OperationKind kind) {
        return (rs, row) -> new Operation(
                rs.getObject("id", UUID.class),
                kind,
                rs.getObject("wallet_id", UUID.class),
                rs.getLong("amount"),
                rs.getString("status"),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getLong("seq"));
    }
}
