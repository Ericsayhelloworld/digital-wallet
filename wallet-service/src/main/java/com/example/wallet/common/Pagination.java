package com.example.wallet.common;

import com.example.wallet.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** API-5: cursor pagination, newest first. The cursor is the position (seq) of the last row shown. */
public final class Pagination {

    public record Page<T>(List<T> items, String nextCursor) {
    }

    private Pagination() {
    }

    public static int limit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit < 1 || limit > 100) {
            throw ApiException.validation("limit", "must be between 1 and 100");
        }
        return limit;
    }

    public static Long decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("cursor", "cannot be decoded");
        }
    }

    public static String encode(long position) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Long.toString(position).getBytes(StandardCharsets.UTF_8));
    }
}
