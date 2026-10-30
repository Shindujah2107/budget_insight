package com.moneyreserve.config;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for Budget Insights.
 *
 * <p>Everything here is configuration rather than a constant because each value is something a
 * reviewer may need to change without a code change. The look-back in particular is a compliance
 * position, not an implementation detail: Compliance approved six months with the possibility of
 * twelve later, so extending it must be a configuration change with its own approval rather than a
 * constant somebody edits.
 *
 * <p>Every flag defaults to off. The feature ships dark and is enabled per environment.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "moneyreserve.insights")
public class InsightsProperties {

  /** Master switch. With this off the Insights endpoints behave as if the feature is absent. */
  private boolean enabled = false;

  /**
   * Months of history Budget Insights will analyse.
   *
   * <p>Six is the approved position. Raising this widens how much of a customer's personal
   * financial history is processed, so it is deliberately visible in configuration rather than
   * buried in a query.
   */
  private int lookbackMonths = 6;

  /**
   * Currency every Insights figure is expressed in.
   *
   * <p>Single-currency by design in v1. A customer whose bank messages arrive in more than one
   * currency would need per-currency totals on the screen, and the design has one total, so mixing
   * them silently would produce a number that means nothing. Configuration rather than a constant
   * because the deployment, not the code, decides what market it serves.
   */
  private String currency = "LKR";

  /** Default percentage of a category limit at which the approaching-limit alert fires. */
  private short defaultCategoryAlertThresholdPct = 80;

  /**
   * Whether a masked card tail may be sent to the backend at all.
   *
   * <p>Held off pending documented Compliance approval. Nothing in the schema can hold more than
   * the last four digits, but this keeps even that behind an explicit decision.
   */
  private boolean maskedCardToBackendEnabled = false;

  /**
   * Salt for the device identifier hash on compliance events.
   *
   * <p>Left blank, the events the <em>server</em> raises are still written, but without a
   * correlatable device hash — the incident record matters more than the ability to group incidents
   * by device.
   *
   * <p><b>For device-reported events (BI-36) a blank salt withholds the route entirely</b>, because
   * there the salt also keys the digest of a client-supplied reference, and an unsalted digest of a
   * client-chosen string is reversible. {@code InsightsComplianceController} answers 501 and the
   * handset holds its queue.
   *
   * <p>One consequence worth knowing before this value is ever changed in a live environment: it is
   * doing three jobs at once. It is the secret, it is what makes BI-36 reporting available, and it
   * keys that route's duplicate suppression. Blanking it to withhold the route also stops device
   * correlation on every server-raised compliance event for as long as it is blank, and those rows
   * are never backfilled; rotating it ends duplicate suppression for reports in flight.
   */
  private String deviceHashSalt = "";

  /**
   * Whether a device hash salt is configured.
   *
   * <p>One predicate in one place. Three callers ask this question — the compliance controller
   * before it accepts a report, the boot-time guard, and the hasher itself — and three copies of
   * {@code salt == null || salt.isBlank()} is three chances for them to disagree about what
   * "configured" means, with the controller saying yes and the hasher throwing.
   */
  public boolean hasDeviceHashSalt() {
    return deviceHashSalt != null && !deviceHashSalt.isBlank();
  }

  /**
   * Time zone every day count on the budget ring is worked out in.
   *
   * <p>The container runs UTC. "Days into your budget" that is computed in UTC is wrong for the
   * five and a half hours after midnight in Colombo every single day, and a customer opening the
   * app late at night is exactly the customer checking whether they have overspent. The market the
   * deployment serves decides this, so it is configuration.
   */
  private String zone = "Asia/Colombo";

  /**
   * The configured zone, resolved.
   *
   * <p>Every date the service treats as "today" comes through here, so the look-back boundary, the
   * default period and the day counts on the ring can never be worked out against different
   * clocks. A zone that is half applied is worse than one that is not applied at all: the two
   * answers disagree only for a few hours around a month boundary, which is exactly when nobody
   * is looking.
   */
  public ZoneId zoneId() {
    return ZoneId.of(zone);
  }

  /**
   * How far above the ideal daily rate a customer may drift before the screen says so.
   *
   * <p>Zero is the published behaviour: any amount over the ideal rate reads as ahead of pace.
   * Raising it adds a band either side, so a few rupees a day over does not turn the ring amber.
   */
  private int paceTolerancePct = 0;

  private final Budget budget = new Budget();

  private final Manual manual = new Manual();

  private final Consent consent = new Consent();

  private final Ingest ingest = new Ingest();

  private final Activity activity = new Activity();

  private final Alerts alerts = new Alerts();

  private final Recommendations recommendations = new Recommendations();

  private final Stub stub = new Stub();

  private final Export export = new Export();

  private final Jobs jobs = new Jobs();

  private final Compliance compliance = new Compliance();

  private final Admin admin = new Admin();

  private final Categorisation categorisation = new Categorisation();

  private final FormatDiscovery formatDiscovery = new FormatDiscovery();

  /**
   * SMS format discovery: learning bank SMS formats no template can read, without receiving any
   * message (see {@code InsightsFormatDiscoveryController}).
   *
   * <p>Two release flags, both off by default, because the phases carry different privacy weight:
   * {@code enabled} accepts daily <i>counts</i> per sender (Phase 1); {@code samplesEnabled}
   * accepts message <i>skeletons</i> (Phase 2) and needs Compliance sign-off and the consent text
   * that covers it. Phase 2 also needs {@code userHashKey}; without it the route answers 501.
   */
  @Getter
  @Setter
  public static class FormatDiscovery {

    /** Phase 1: POST /formats/unmatched-stats and the admin coverage report. */
    private boolean enabled = false;

    /** Phase 2: POST /formats/samples, the vocabulary in reference sync, format candidates. */
    private boolean samplesEnabled = false;

    /**
     * Distinct users before an admin may see a format. Values below 5 are ignored: see {@link
     * #effectiveMinDistinctUsers()}.
     */
    private int minDistinctUsers = 5;

    /**
     * Local testing only: a lower floor, so one developer's phone can produce a format candidate.
     * Unset everywhere else; {@link FormatDiscoveryLocalGuard} refuses to start outside local and
     * test when it is set.
     */
    private Integer localMinDistinctUsers;

    /** How long one user's sample of a format is kept. */
    private int sampleTtlDays = 90;

    /** How long per-device daily counts are kept. */
    private int statRetentionDays = 90;

    private int maxStatsPerRequest = 100;

    private int maxSamplesPerRequest = 20;

    /** Samples one user may send in a day; a looping handset is stopped here. */
    private int maxSamplesPerUserPerDay = 50;

    /**
     * HMAC key for {@code bi_sms_format_sample.user_hash}. A secret, from Secrets Manager. Never
     * rotate it while samples exist: withdrawal and erasure find a user's samples by recomputing
     * the hash, and a new key would orphan them until they expire.
     */
    private String userHashKey = "";

    public boolean hasUserHashKey() {
      return userHashKey != null && userHashKey.length() >= 32;
    }

    /**
     * The k-anonymity floor. Configuration can raise it, never lower it below 5, except with
     * {@code localMinDistinctUsers} on a local or test profile.
     */
    public int effectiveMinDistinctUsers() {
      if (localMinDistinctUsers != null && localMinDistinctUsers >= 1) {
        return localMinDistinctUsers;
      }
      return Math.max(5, minDistinctUsers);
    }
  }

  /**
   * Categorisation layer 0: message types decided by type alone, before any merchant rule.
   *
   * <p>For messages whose merchant text is not a merchant at all: an ATM receipt carries the ATM's
   * location ("KANDY BUS STAND"), a CRM deposit the branch, an account charge the fee description.
   * Matching keywords against those files cash under Transport. Values are category codes; a code
   * that is missing or inactive falls through to the merchant rules (logged). Adding a Cash/ATM
   * category later is a change of value here, not of code.
   *
   * <p>In YAML write the keys in brackets so Spring keeps the underscores, e.g. {@code
   * "[ATM_RECEIPT]": CASH}; configured entries are merged with these defaults.
   */
  @Getter
  @Setter
  public static class Categorisation {

    private Map<String, String> messageTypeCategories =
        com.moneyreserve.service.calc.MessageTypeCategories.defaults();
  }

  /**
   * Contract-first stub mode.
   *
   * <p>With this on, the Insights endpoints answer from a fixture set built from the real response
   * types, so the frontend can integrate against the published contract before the logic behind it
   * exists. It is refused outside known non-production profiles — see {@code
   * InsightsStubModeGuard}.
   */
  @Getter
  @Setter
  public static class Stub {
    private boolean enabled = false;
  }

  /**
   * The R2 release gate: the monthly target, the summary ring and the set-a-budget screen.
   *
   * <p>Separate from {@link #enabled} because the master switch and a release are different
   * decisions. With Insights on and this off, the screen is present and the summary answers 501 —
   * which is what it did before R2 existed, so turning the release off is a true rollback rather
   * than a partial one.
   */
  @Getter
  @Setter
  public static class Budget {
    private boolean enabled = false;
  }

  /**
   * The R1 release gate: manually entered transactions.
   *
   * <p>DIP line 1648 names this flag and the service shipped without it, which left manual add,
   * edit, re-categorise and delete as the one release with no rollback of its own. Every other
   * release can be switched off and answer 501 as it did before it existed; until this was added,
   * withdrawing manual entry meant withdrawing Budget Insights entirely.
   *
   * <p>Note what this does <b>not</b> gate. Reading a transaction, the breakdown and the summary
   * are R1 read paths that survive a rollback of manual entry, and ingested transactions are R4's
   * concern behind {@code ingest}. This gate is write access by hand, nothing else: with it off a
   * customer can still see everything they have, they just cannot add to it or change it.
   */
  @Getter
  @Setter
  public static class Manual {
    private boolean enabled = false;
  }

  /**
   * The R6 50/30/20 recommendations (BI-26).
   *
   * <p><b>The mapping is configuration because it is a business rule.</b> Which of the seventeen
   * categories counts as a need, a want or saving is a judgement about how people in this market
   * live, not a property of the category, and it is not written down anywhere in the plan. Holding
   * it here means changing it is a config change rather than a release — and means the decision is
   * visible to whoever owns it rather than buried in a constant.
   *
   * <p>A category in none of the three lists is unbucketed. {@code OTHER} is deliberately in none:
   * it is the fallback the categoriser uses when it cannot tell, and forcing it into a bucket would
   * distort every percentage on the panel with spend nobody has classified. {@code TRANSFERS} is
   * also left out — a transfer might be saving, might be supporting family, might be moving money
   * between the customer's own accounts, and this service cannot see which.
   *
   * <p><b>The percentages are of the customer's monthly target, not of their income.</b> Budget
   * Insights never sees income. Splitting the target is what makes the third bucket meaningful:
   * the 20% is money deliberately left unspent, which is observable as target minus spend, whereas
   * a "savings" figure built from outgoing categories alone would tell a customer who saves by
   * standing order that they save nothing.
   *
   * <p>Separate from {@code alerts} even though the plan names one flag for R6, because the two
   * features fail and roll back independently and a recommendations panel served behind a flag
   * called "alerts" is a flag nobody will read correctly at three in the morning.
   */
  @Getter
  @Setter
  public static class Recommendations {

    private boolean enabled = false;

    /** Share of the monthly target the needs bucket should not exceed. */
    private int needsPct = 50;

    /** Share for wants. */
    private int wantsPct = 30;

    /** Share left unspent. needsPct + wantsPct + savingsPct should be 100. */
    private int savingsPct = 20;

    /** How many complete months before the current one the suggested caps are drawn from. */
    private int trailingMonths = 3;

    private java.util.List<String> needs = new java.util.ArrayList<>();
    private java.util.List<String> wants = new java.util.ArrayList<>();
    private java.util.List<String> savings = new java.util.ArrayList<>();
  }

  /**
   * The R6 release gate: threshold alerts, and the endpoints that read them.
   *
   * <p>With this off, nothing evaluates and nothing is written -- the summary still computes
   * APPROACHING and EXCEEDED for the card, because that is R2 and is not gated here. What the flag
   * controls is whether a crossing is remembered and told to the customer once.
   *
   * <p>§24 is explicit that a crossing raises an in-app alert and no external message. Nothing
   * behind this flag sends SMS, push or email, and there is no code path from it to one.
   */
  @Getter
  @Setter
  public static class Alerts {
    private boolean enabled = false;
  }

  /**
   * The R5 release gate: the payment activity chart and its account filter.
   *
   * <p>Separate from the master switch for the same reason as the others: with Insights on and
   * this off, /activity answers 501 exactly as it did before R5 existed, which is a true rollback
   * of one chart rather than of the screen.
   *
   * <p>The chart draws from {@code bi_account.instrument_type}, so it shows nothing for a customer
   * whose spend carries no account. While {@code masked-card-to-backend-enabled} is off that is
   * every ingested card transaction, and the card series stays empty however much real data
   * arrives — which is a Compliance decision surfacing in the UI, not a defect here.
   */
  @Getter
  @Setter
  public static class Activity {
    private boolean enabled = false;
  }

  /**
   * The R4 release gate: SMS ingest and the reference sync that feeds the device's parser.
   *
   * <p>Separate from {@link #enabled} and from {@code consent} because ingest is the release that
   * carries compliance gate 2. With Insights on, consent on and this off, the screen and the budget
   * work exactly as they did at R3 and the two ingest routes answer 501 — a true rollback of the
   * SMS path on its own, which is the thing most likely to need one.
   */
  @Getter
  @Setter
  public static class Ingest {

    private boolean enabled = false;

    /**
     * Messages a device may send in one batch.
     *
     * <p>A bound on the unit of work rather than a policy: the whole batch is one transaction, and
     * an unbounded one from a handset returning after months offline would hold locks for as long
     * as it took to write.
     */
    private int maxBatchSize = 200;

    /**
     * Messages one device may ingest per window before it is throttled.
     *
     * <p>Zero disables the limiter. See {@code IngestRateLimiter} for why this ceiling is
     * approximate across more than one task.
     */
    private int rateLimitMessages = 600;

    /** Length of the rate-limit window, in seconds. */
    private int rateLimitWindowSeconds = 60;

    /**
     * Message types this deployment will accept. Empty means accept any well-formed type.
     *
     * <p>Whether account-balance messages are in scope at all is an open Compliance decision
     * (dependency D-3). Holding the set here is what keeps that decision a configuration change:
     * the day it is answered, a balance message becomes ingestable by adding one string, and until
     * then a device that starts sending them cannot widen what this service stores.
     */
    private java.util.List<String> acceptedMessageTypes = new java.util.ArrayList<>();
  }

  /**
   * The R3 release gate: consent, and the data-rights endpoints beneath it.
   *
   * <p>{@code version} is the policy version the service currently requires. A grant naming any
   * other version is refused: the customer must have been shown the text that is in force, and a
   * grant against a superseded policy proves nothing. Raising this value invalidates every existing
   * consent at once, which is the intended effect when the policy text changes.
   */
  @Getter
  @Setter
  public static class Consent {
    private boolean enabled = false;
    private String version = "1.0";
  }

  /**
   * BI-27 data export.
   *
   * <p>The retention window is the whole of this feature's privacy position. An export is a second
   * copy of everything the service holds about one customer, in one row, and the only thing
   * limiting the exposure it creates is how long that row is allowed to exist. It is configuration
   * rather than a constant so it can be shortened without a release, and so that lengthening it is
   * a visible act with an owner.
   */
  @Getter
  @Setter
  public static class Export {

    /** Hours a generated export stays downloadable before the retention job removes it. */
    private int retentionHours = 24;
  }

  /**
   * Scheduled jobs.
   *
   * <p>Off only for local development, where two services against one database is a normal
   * afternoon. Retention does not follow the master feature flag -- data already captured must go
   * on being expired whether or not customers can see the screen.
   */
  @Getter
  @Setter
  public static class Jobs {

    private boolean enabled = true;

    /** Daily, early, in the configured zone. */
    private String retentionCron = "0 15 2 * * *";

    /** Daily, after the purge, so a refreshed aggregate is never built over rows about to go. */
    private String refreshCron = "0 45 2 * * *";
  }

  /**
   * BI-36: the route a handset reports a discarded OTP or PIN on.
   *
   * <p><b>No release flag.</b> Unlike every other capability here, this one is not something a
   * customer sees and not something a rollback would spare them: it is the evidence that a control
   * already running on every handset is working. Gating it would mean the option of running the
   * filter and not recording it, which is the state this ticket exists to end. It is therefore
   * available whenever Budget Insights itself is, subject to consent.
   *
   * <p>What does gate it is {@code device-hash-salt}, because without one the client reference
   * cannot be stored as anything a client could not reverse. With no salt configured the route
   * answers 501 and the device holds its queue — see {@code InsightsComplianceController}.
   */
  @Getter
  @Setter
  public static class Compliance {

    /** Events a device may report in one batch. A bound on the unit of work, as on ingest. */
    private int maxBatchSize = 200;

    /**
     * Events one customer may report per window before they are throttled.
     *
     * <p>Zero disables the limiter. {@code bi_compliance_event} is the one table in this schema
     * that is never purged, so an unbounded writer into it is worth a brake even though the
     * ceiling is approximate across more than one task -- see {@code IngestRateLimiter}.
     */
    private int rateLimitMessages = 600;

    /** Length of the rate-limit window, in seconds. */
    private int rateLimitWindowSeconds = 60;

    /**
     * How far ahead of the server a device's clock may be before its report is refused.
     *
     * <p>{@code occurred_at} is the device's timestamp, not the server's, so that a handset which
     * was offline for a day does not file yesterday's incidents under today. The price of trusting
     * that clock is that it has to be bounded: a detection dated next month would sit in the
     * record as a future incident nobody can explain.
     */
    private int clockSkewMinutes = 10;
  }

  /**
   * Budget Insights admin dashboard ({@code moneyreserve.insights.admin.*}).
   *
   * <p>The admin API is always on wherever the master switch above is on; it has no enable switch
   * of its own (a {@code moneyreserve.insights.admin.enabled} setting is ignored).
   * {@code config-write-enabled=false} (default on) freezes Configuration: drafts, discards,
   * publish and restore answer 403 {@code MR.ADM.E.004} while reads keep working.
   *
   * <p>The HTTP Basic {@code username}/{@code password} under the same prefix are read by
   * {@code SecurityConfig}, not here.
   */
  @Getter
  @Setter
  public static class Admin {

    private boolean configWriteEnabled = true;

    /** Admin writes (drafts, discards, publish, restore) per Super Admin per minute; 0 = off. */
    private int writesPerMinute = 60;

    /** "Test this rule" calls per Super Admin per minute; 0 = off. */
    private int ruleTestsPerMinute = 30;

    /** Template builder calls (analyse, generate, validate) per Super Admin per minute; 0 = off. */
    private int builderCallsPerMinute = 120;

    /** Merchant names and impact counts use only merchants seen for this many customers (min 5). */
    private int merchantMinCustomers = 5;

    /** Icon keys the mobile app ships (the V1.0.1 set); a category must use one of them. */
    private List<String> iconKeys =
        new ArrayList<>(
            List.of(
                "ic_transport",
                "ic_utilities",
                "ic_health_care",
                "ic_debt_payments",
                "ic_dining_out",
                "ic_entertainment",
                "ic_personal_care",
                "ic_shopping",
                "ic_gifts_donation",
                "ic_education",
                "ic_work",
                "ic_travel",
                "ic_gym",
                "ic_subscriptions",
                "ic_transfers",
                "ic_bills_utilities",
                "ic_other"));

    /**
     * Initial bank list only. Since V1.6.0 the banks live in {@code bi_bank} and are managed from
     * the Admin Dashboard ({@code /admin/config/banks}); this map is read only to seed an empty
     * registry in profiles without Flyway (see {@code BankRegistryBootstrap}). Changing it has no
     * effect on an environment whose registry already has rows.
     */
    private Map<String, String> banks =
        new LinkedHashMap<>(
            Map.of(
                "7056", "Commercial Bank",
                "7083", "Hatton National Bank",
                "7010", "Bank of Ceylon",
                "7287", "Seylan Bank",
                "7463", "Amana Bank",
                "7278", "Sampath Bank"));

    public int effectiveMerchantMinCustomers() {
      return Math.max(5, merchantMinCustomers);
    }
  }
}
