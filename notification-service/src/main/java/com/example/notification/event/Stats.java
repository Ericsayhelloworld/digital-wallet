package com.example.notification.event;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** NTF-5: counters that start at zero when the service starts. */
@Component
public class Stats {

    public record View(long eventsProcessed, long duplicatesIgnored, long deadLettered) {
    }

    final AtomicLong eventsProcessed = new AtomicLong();
    final AtomicLong duplicatesIgnored = new AtomicLong();
    final AtomicLong deadLettered = new AtomicLong();

    public View view() {
        return new View(eventsProcessed.get(), duplicatesIgnored.get(), deadLettered.get());
    }
}
