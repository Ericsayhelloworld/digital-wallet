package com.example.auth.api;

import com.example.auth.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SEC-9: at most N login attempts per username per window (default 5 per 60 seconds).
 * The state is kept in memory, which the specification allows.
 */
@Component
public class LoginRateLimiter {

    private final int maxAttempts;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(@Value("${app.login-rate-limit.max-attempts:5}") int maxAttempts,
                            @Value("${app.login-rate-limit.window-seconds:60}") long windowSeconds) {
        this.maxAttempts = maxAttempts;
        this.windowMillis = windowSeconds * 1000;
    }

    /** Records one attempt, or throws 429 RATE_LIMITED if the username has used up its attempts. */
    public void check(String username) {
        long now = System.currentTimeMillis();
        Deque<Long> times = attempts.computeIfAbsent(username, key -> new ArrayDeque<>());
        synchronized (times) {
            // forget attempts that are older than the window (a sliding window)
            while (!times.isEmpty() && now - times.peekFirst() >= windowMillis) {
                times.pollFirst();
            }
            if (times.size() >= maxAttempts) {
                long waitMillis = times.peekFirst() + windowMillis - now;
                int retryAfter = (int) Math.max(1, (waitMillis + 999) / 1000);
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts",
                        "Too many login attempts for this username. Try again in " + retryAfter + " seconds.")
                        .retryAfter(retryAfter);
            }
            times.addLast(now);
        }
    }
}
