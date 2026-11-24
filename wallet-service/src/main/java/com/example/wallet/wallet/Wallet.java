package com.example.wallet.wallet;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Wallet(UUID id, UUID userId, String username, String displayName, long balance,
                     OffsetDateTime createdAt) {
}
