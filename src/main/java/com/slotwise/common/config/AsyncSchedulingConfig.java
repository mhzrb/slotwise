package com.slotwise.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} jobs (notification dispatch, token cleanup) and
 * {@code @Async} listeners. Spring Boot provides the auto-configured
 * {@code applicationTaskExecutor} for async work.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableAsync
public class AsyncSchedulingConfig {
}
