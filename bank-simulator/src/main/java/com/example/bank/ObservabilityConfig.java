package com.example.bank;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    /** OPS-5 / OPS-7: publish latency buckets for every endpoint, so Grafana can compute the p95. */
    @Bean
    public MeterFilter httpLatencyHistogram() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (id.getName().startsWith("http.server.requests")) {
                    return DistributionStatisticConfig.builder().percentilesHistogram(true).build().merge(config);
                }
                return config;
            }
        };
    }

    /** OPS-6: no traces for health checks and Prometheus scrapes, so Zipkin shows only real requests. */
    @Bean
    public ObservationPredicate noActuatorTraces() {
        return (name, context) -> {
            if (name.startsWith("spring.security.")) {
                return false;
            }
            return !(context instanceof ServerRequestObservationContext request
                    && request.getCarrier().getRequestURI().startsWith("/actuator"));
        };
    }
}
