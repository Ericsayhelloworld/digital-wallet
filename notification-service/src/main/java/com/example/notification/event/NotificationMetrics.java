package com.example.notification.event;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * OPS-5: the counters of NTF-5 in the Prometheus format:
 * notification_events_processed_total, notification_duplicates_ignored_total, notification_dead_lettered_total
 */
@Component
public class NotificationMetrics {

    public NotificationMetrics(MeterRegistry registry, Stats stats) {
        FunctionCounter.builder("notification.events.processed", stats.eventsProcessed, AtomicLong::get)
                .description("Events that created notifications").register(registry);
        FunctionCounter.builder("notification.duplicates.ignored", stats.duplicatesIgnored, AtomicLong::get)
                .description("Events that were delivered again and ignored").register(registry);
        FunctionCounter.builder("notification.dead.lettered", stats.deadLettered, AtomicLong::get)
                .description("Messages written to the dead-letter topic").register(registry);
    }
}
