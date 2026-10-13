package com.moipay.budgetinsights.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns Spring's scheduler on.
 *
 * <p>It arrives with BI-30, the first job that has real work to do, rather than with the lock that
 * guards it. A scheduler enabled a phase early would have been configuration in production that did
 * nothing, and the thing most likely to go wrong with scheduling — two instances running the same
 * job — is not demonstrated by a scheduler with nothing scheduled.
 *
 * <p>Switchable off for local development, where two services pointed at one database is a normal
 * afternoon and a purge firing under a debugger is not wanted. It is not a production control: see
 * {@link com.moipay.budgetinsights.service.jobs.InsightsRetentionJob} on why retention does not
 * follow the feature flag.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "moipay.budget-insights.jobs",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SchedulingConfig {}
