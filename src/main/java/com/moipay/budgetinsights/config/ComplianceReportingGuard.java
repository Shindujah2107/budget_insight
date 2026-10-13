package com.moipay.budgetinsights.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says at boot whether device compliance reporting (BI-36) can actually record anything.
 *
 * <p>The route has no release flag — it is the evidence that a control already running on every
 * handset is working, and a flag would only buy the option of running the control and not
 * recording it. What it does have is one prerequisite: {@code device-hash-salt}. Without a salt the
 * client reference cannot be stored as a digest the client could not reverse, and
 * {@code bi_compliance_event} is the one table in this schema that is never purged — not on consent
 * withdrawal, not on erasure. So with no salt the route answers 501 and the device holds its queue.
 *
 * <p><b>This logs; it does not refuse to start.</b> An earlier version threw, in the fail-closed
 * style of {@link InsightsStubModeGuard}, and that was the wrong shape once the flag went away.
 * Without a flag the condition is reachable in an ordinary deployment — {@code device-hash-salt}
 * defaults to blank in both AWS profiles — and refusing to boot over it would take ingest, the
 * budget, the dashboard, consent, data rights and both scheduled jobs down with it. Withholding one
 * route is the proportionate answer; stopping the service is not.
 *
 * <p>The line is logged at ERROR rather than WARN on purpose: "the handsets are filtering OTPs and
 * nothing is being recorded" is a compliance gap, not a configuration nicety, and it should be
 * alerted on rather than merely collected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplianceReportingGuard {

  private final InsightsProperties properties;

  @EventListener(ApplicationReadyEvent.class)
  public void verify() {
    // Budget Insights off means the route answers 404 and can write nothing, so a blank salt is
    // not a gap and saying so would be noise on every boot of an environment that is switched off.
    if (!properties.isEnabled()) {
      return;
    }
    if (properties.hasDeviceHashSalt()) {
      log.info("Budget Insights: device compliance reporting is recording (BI-36)");
      return;
    }
    log.error(
        "Budget Insights: device compliance reporting is NOT recording. "
            + "moipay.budget-insights.device-hash-salt is not set, so POST "
            + "/api/insights/compliance/events answers 501 and every OTP or PIN a handset "
            + "discards goes unrecorded. Set BUDGET_INSIGHTS_DEVICE_HASH_SALT to close the gap; "
            + "no release or client change is needed.");
  }
}
