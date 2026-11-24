package com.example.wallet.bank;

/** A bank transaction as the bank returns it and as it sends it in a webhook (Section 6.6). */
public record BankTransaction(String bankTransactionId, String reference, String type, long amount,
                              String status, String reason) {

    public boolean isFinal() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status);
    }
}
