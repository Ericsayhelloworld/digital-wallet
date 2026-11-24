package com.example.bank;

import com.example.bank.error.ApiException;

/** Section 6.6: the failure settings. A field that is missing in PUT takes its default. */
public record FailureSettings(Boolean down, Long responseDelayMs, Double hangRate, Long settleDelayMs,
                              Double declineRate, Integer webhookCopies) {

    public static FailureSettings defaults() {
        return new FailureSettings(false, 50L, 0.0, 500L, 0.0, 1);
    }

    /** Fills missing fields with defaults and checks the ranges. */
    public FailureSettings withDefaults() {
        FailureSettings d = defaults();
        FailureSettings s = new FailureSettings(
                down != null ? down : d.down,
                responseDelayMs != null ? responseDelayMs : d.responseDelayMs,
                hangRate != null ? hangRate : d.hangRate,
                settleDelayMs != null ? settleDelayMs : d.settleDelayMs,
                declineRate != null ? declineRate : d.declineRate,
                webhookCopies != null ? webhookCopies : d.webhookCopies);
        if (s.responseDelayMs < 0) throw ApiException.validation("responseDelayMs", "must be 0 or more");
        if (s.settleDelayMs < 0) throw ApiException.validation("settleDelayMs", "must be 0 or more");
        if (s.webhookCopies < 0) throw ApiException.validation("webhookCopies", "must be 0 or more");
        if (s.hangRate < 0 || s.hangRate > 1) throw ApiException.validation("hangRate", "must be between 0 and 1");
        if (s.declineRate < 0 || s.declineRate > 1) throw ApiException.validation("declineRate", "must be between 0 and 1");
        return s;
    }
}
