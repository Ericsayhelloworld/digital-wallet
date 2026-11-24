package com.example.wallet.operation;

/** Deposits and withdrawals work the same way; this says which table and which bank type to use. */
public enum OperationKind {
    DEPOSIT("deposits", "CHARGE"),
    WITHDRAWAL("withdrawals", "PAYOUT");

    /** A fixed table name, never user input. */
    public final String table;
    public final String bankType;

    OperationKind(String table, String bankType) {
        this.table = table;
        this.bankType = bankType;
    }

    public static OperationKind fromBankType(String bankType) {
        for (OperationKind kind : values()) {
            if (kind.bankType.equals(bankType)) {
                return kind;
            }
        }
        return null;
    }
}
