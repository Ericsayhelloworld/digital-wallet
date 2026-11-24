package com.example.wallet.config;

import com.example.wallet.error.ApiException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** MNY-2, MNY-3 and MNY-4: amount limits, read from application.yml. */
@ConfigurationProperties(prefix = "app.limits")
public record Limits(long minAmount, long maxAmount, long dailyLimit) {

    public void checkAmount(long amount) {
        if (amount < minAmount || amount > maxAmount) {
            throw ApiException.validation("amount",
                    "must be between " + minAmount + " and " + maxAmount + " cents");
        }
    }
}
