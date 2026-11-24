package com.example.notification.common;

import java.util.Optional;
import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    /** IDs arrive as strings (API-2); a string that is not a UUID simply matches nothing. */
    public static Optional<UUID> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
