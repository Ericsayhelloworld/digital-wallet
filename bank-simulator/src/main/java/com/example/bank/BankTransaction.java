package com.example.bank;

/** Section 6.6: returned by both /bank/v1 endpoints and sent as the webhook body. */
public record BankTransaction(String bankTransactionId, String reference, String type, long amount,
                              String status, String reason) {
}
