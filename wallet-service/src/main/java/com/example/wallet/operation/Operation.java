package com.example.wallet.operation;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A deposit or a withdrawal. */
public record Operation(UUID id, OperationKind kind, UUID walletId, long amount, String status,
                        String failureReason, OffsetDateTime createdAt, OffsetDateTime completedAt, long seq) {

    /** The response body of both deposits and withdrawals (Section 6.3). */
    public record Response(String id, long amount, String currency, String status, String failureReason,
                           OffsetDateTime createdAt, OffsetDateTime completedAt) {
    }

    public Response toResponse() {
        return new Response(id.toString(), amount, "USD", status, failureReason, createdAt, completedAt);
    }
}
