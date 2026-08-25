package com.zlecaf.escrow.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's {@code @Scheduled} support so the bounded-retention purge
 * ({@link com.zlecaf.escrow.scheduler.PartnerNoncePurger}) and the
 * anti-bruteforce eviction ({@link com.zlecaf.escrow.security.AuthRateLimiter})
 * actually fire. Scheduling was not enabled anywhere before Story 3.4.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /** Horloge injectable — les composants sensibles au temps la reçoivent pour être testables. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
