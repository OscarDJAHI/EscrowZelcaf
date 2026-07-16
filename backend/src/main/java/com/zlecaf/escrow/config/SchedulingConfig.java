package com.zlecaf.escrow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's {@code @Scheduled} support so the bounded-retention purge
 * ({@link com.zlecaf.escrow.scheduler.PartnerNoncePurger}) actually fires. No
 * other scheduled task exists yet; scheduling was not enabled anywhere before
 * Story 3.4.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
