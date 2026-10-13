# R7 preflight remediation — final report

20 September 2026. Baseline `8ddeccc` (R6). **Nothing committed, nothing pushed.**

Final build: **212 unit tests, 45 integration tests, 0 failures, 2 skipped.** The two skips are the
D-1 corpus gate and are supposed to be there.

---

| Item | Status | Evidence | Files changed | Tests | Remaining risk |
| --- | --- | --- | --- | --- | --- |
| 1. GatewayAuthFilter registration | **FIXED** | Pre-fix failure printed `gatewayAuthFilter -> com.moipay.budgetinsights.config.GatewayAuthFilter` in the live container filter list, with the flag both on and off | `SecurityConfig` | `GatewayAuthFilterRegistrationIT` (3), `GatewayAuthFilterDisabledIT` (2), `GatewayHeaderTrustIT` (3) | Three sibling services carry the identical defect |
| 2. Default credentials and tokens | **FIXED** | No literal credential remains in `src/main`; `SecurityConfig` logs a warning and issues an unrecoverable random password when unset | `SecurityConfig`, `GatewayAuthFilter`, `OriginVerifyFilter`, `OpenApiConfig`, `application.yml` | Covered indirectly by `GatewayHeaderTrustIT`, which must supply credentials explicitly | Sibling services still ship the same literals |
| 3. CORS wildcard + credentials | **FIXED** | `allowedOriginPatterns("*")` replaced by `moipay.cors.allowed-origins`, the FeeEngine/CaseEngine pattern | `SecurityConfig`, `application.yml` | `CorsPolicyIT` (4) | Real origins not yet set on any task definition |
| 4. DIP ↔ code flag naming | **RECONCILED** | Code matches `moipay.<service>.*` as CaseEngine and SplitEngine do; DIP's `moipay.insights.*` matches no service and no env var | `InsightsComplianceServiceImpl`, `docs/CONFIGURATION.md` | — | The DIP itself still says the wrong thing; 8 lines listed for correction |
| 5. Manual feature flag | **ADDED** | 501 on all three write routes with the flag off; reads unaffected | `InsightsProperties`, `ManualEntryGate`, `InsightsWebConfig`, `InsightsTransactionController`, 4 profiles | `ManualFlagDisabledIT` (4), `ManualFlagEnabledIT` (3), `MasterSwitchIT` (2) | Not wired in Terraform until the DEV change is applied |
| 6. LOCAL flags + stub consistency | **DONE** | All eight implemented flags true; `stub.enabled: false` | `application-local.yml` | — | Mobile team may rely on the fixtures; one line reverts |
| 6b. AWS DEV flags | **PREPARED, NOT APPLIED** | Live task definition rev 7 read: `BUDGET_INSIGHTS_ENABLED=false`, seven release flags absent | `MoiPay-Infrastructure/environments/dev/main.tf` | — | Requires your `terraform plan` review and apply |
| 7. Integration-test foundation | **BUILT** | Failsafe bound to `integration-test` **and** `verify`; 45 tests execute and fail the build | `pom.xml`, `application-test.yml`, 11 test classes | The whole suite | No database-level tests; Testcontainers deferred |
| 8. POM quality gates | **PARTIAL** | JaCoCo merged report produced, 80 classes; dependency-check configured behind `NVD_API_KEY` | `pom.xml` | — | No coverage threshold yet; no key so no scan has run; Spotless/Checkstyle/SpotBugs/PMD deferred |
| 9. Flyway staging | **FIXED** | Migrations V1.0.0→V1.1.0 strictly ascending; `environments/staging/main.tf` has no `budget_insights` module, so no history to conflict with | `application-aws-staging.yml` | — | None |
| 10. External blockers | **UNTOUCHED** | `masked-card-to-backend-enabled: false` unchanged; the two D-1 tests still abort | — | — | Both still block Gate 2 evidence and the R5/R6 demos |
| 11. R6 two-flag deviation | **DOCUMENTED** | Independence demonstrated, not asserted | `docs/CONFIGURATION.md` | `R6FlagIndependenceIT` (2) | Needs sign-off |
| 12a. Swagger | **FIXED** | Staging defaults off; dev keeps it on for the integration teams | `application-aws-staging.yml` | — | None |
| 12b. Origin-verify staging | **FIXED** | Staging had no `moipay.security` block; Terraform injects the variables and nothing read them | `application-aws-staging.yml` | — | Untestable until staging exists |

---

## A. Fixed technical FAILs

**1 — `GatewayAuthFilter` registered twice.** A `Filter` that is also a `@Component` is registered
by Spring Boot against the servlet container at `/*` whatever any flag says. With
`gateway-auth-enabled=true` it ran twice per request; with it `false` it still ran. The filter's
own javadoc claimed otherwise. Proven against a started container before the fix — the failure
message printed the container's filter list — then closed with a `FilterRegistrationBean` carrying
`setEnabled(false)`.

The consequence worth understanding: the stray copy runs innermost, after Spring Security has
authorized and immediately before the dispatcher, which is where `@AuthenticationPrincipal`
resolves. With gateway auth off, a caller past HTTP Basic could have had their principal replaced
by headers naming any user id. **That path is reasoned from the proven registration plus filter
ordering. It was not separately reproduced,** and it is not described here as demonstrated.
`GatewayHeaderTrustIT` now fixes the requirement either way.

**2 — Credentials and tokens with literal defaults.** `application.yml` shipped a working admin
username and password in the always-loaded base profile; `SecurityConfig` carried the same pair as
`@Value` fallbacks, so deleting the YAML alone would not have closed it; `GatewayAuthFilter` and
`OriginVerifyFilter` each carried a literal gateway token. **And `OpenApiConfig` printed the
username and password in the Swagger page's Basic-auth description** — found by grepping for the
literals after the edit, not by the preflight. Nothing configured now yields an account with an
unrecorded random password: it cannot be authenticated as, and the service still boots, because
every AWS profile runs with gateway auth on where the account is unreachable anyway.

**3 — CORS.** `allowedOriginPatterns("*")` with `allowCredentials(true)` meant any site a customer
visited could call these endpoints with their credentials attached, against a service holding bank
transactions. Replaced with the allowlist pattern FeeEngine and CaseEngine already use. No domain
invented. The mobile app is a native client, sends no `Origin` header, and was never affected.

**5 — Missing R1 flag, and the ordering defect behind it.** The gate began inside the controller
and `ManualFlagDisabledIT` caught what that produced: **400, not 501**, because `@Valid` binds and
validates before the method body. A client could not distinguish "this capability is withdrawn"
from "your payload is wrong" — the one distinction the 501 exists to make. Moved to a
`HandlerInterceptor`, whose `preHandle` runs before argument resolution.

Then `MasterSwitchIT` caught the same thing one level up: with the master switch off, a malformed
body returned **400 rather than 404**, so "Budget Insights is absent" failed exactly where a client
would probe. The interceptor now decides both gates, master first.

**9 — Flyway on staging.** Checked before changing: strictly ascending migrations and no staging
deployment at all. DEV logs `outOfOrder mode is active — migration may not be reproducible` on
every boot, which is correct for dev and wrong for a promotion rehearsal.

**12 — Swagger and origin-verify.** Staging Swagger defaults off; the old comment said "set to
false before production promotion", and a checklist item that must be remembered eventually isn't.
Staging had **no `moipay.security` block at all**, so the `ORIGIN_VERIFY_*` variables Terraform
injects into every task were read by nobody — origin verification would have been off in staging
whatever the infrastructure said.

---

## B. New tests added

45 integration tests across 11 classes, plus the Failsafe foundation. Every one asserts a claim
that could previously have been false with a green build.

| Class | Tests | What it fixes |
| --- | --- | --- |
| `GatewayAuthFilterRegistrationIT` | 3 | One registration, in the security chain, flag on |
| `GatewayAuthFilterDisabledIT` | 2 | "Disabled" means the filter does not execute |
| `GatewayHeaderTrustIT` | 3 | Gateway headers establish nothing when gateway auth is off |
| `CorsPolicyIT` | 4 | Allowlist admits, unknown and near-miss origins refused |
| `AuthorizationBoundaryIT` | 6 | Five refusals **and one admission** |
| `OwnershipIsolationIT` | 4 | One customer's money is invisible to another |
| `ReleaseEndpointsIT` | 7 | Every R1–R6 route is mapped and answers |
| `ReleaseFlagOffIT` | 5 | Each flag withdraws its own release only |
| `R6FlagIndependenceIT` | 2 | The two R6 flags are genuinely separate |
| `ManualFlagDisabledIT` / `EnabledIT` | 7 | The gate is a gate in both positions |
| `MasterSwitchIT` | 2 | 404 outranks 501 |

`OwnershipIsolationIT` is the one to read. There are no scopes here — the gateway says who you are
and every query is scoped by that id. A finder that forgot its owner would not throw or log; it
would hand one customer another's transactions, and no prior test would have noticed because they
all use a single user.

Several classes carry a deliberate control: `AuthorizationBoundaryIT` admits a valid caller, and
`ManualFlagEnabledIT` exists because `ManualFlagDisabledIT` would pass against a gate wired shut.

---

## C. Configuration changes

| File | Change |
| --- | --- |
| `application.yml` | admin credentials removed; `moipay.cors` added; `manual` flag added |
| `application-local.yml` | all eight flags true; **`stub.enabled: false`**; gateway token relabelled and made overridable |
| `application-aws-dev.yml` | `manual` flag added |
| `application-aws-staging.yml` | `manual` flag; Flyway `out-of-order` → false; Swagger → false; `origin-verify` block added |
| `application-test.yml` | new — the first Spring-booting test profile this repo has had |
| `pom.xml` | Failsafe; JaCoCo (4 executions); `security-scan` profile |
| `environments/dev/main.tf` | nine flags explicit — **prepared, not applied** |

**Local stub mode is off**, and it is a behaviour change. Stub mode existed so the frontend could
integrate before the logic did; for R1–R6 that logic exists. Leaving it on gave local a split
personality — `/summary`, `/breakdown` and `/activity` from fixtures while `/alerts` and
`/recommendations` served the database, with nothing on screen saying which. One line reverts it.

---

## D. Remaining deviations requiring sign-off

1. **R6 ships two flags where the DIP names one.** `alerts.enabled` and `recommendations.enabled`.
   Deliberate: the features fail and roll back independently, and a recommendations panel behind a
   flag called "alerts" is a flag nobody reads correctly at 3am. `R6FlagIndependenceIT` demonstrates
   the independence rather than asserting it. Affected: DIP §24 line 1724, §25 R6 row.
2. **The DIP's flag prefix is wrong throughout.** Eight lines name `moipay.insights.*`; no such
   property exists. The code follows the convention three sibling services use. Corrections listed
   in `docs/CONFIGURATION.md`. This is a documentation error, not a deviation — but it had already
   leaked into an operator-facing log line.

---

## E. External blockers

1. **Masked-card Compliance approval** — unchanged, as instructed. Blocks the DIP §24 R5 demo (the
   card series stays empty against real data) and the R6 iOS demo.
2. **D-1 labelled SMS corpus** — still absent. `OtpLeakageReleaseGateTest` aborts its two per-bank
   tests on every build; they are the 2 skips. **Compliance gate 2 cannot pass**, which is why
   ingest is held off in the prepared DEV change.

Neither was touched, weakened or worked around.

---

## F. Issues requiring your decision

1. **Apply the DEV Terraform.** Prepared at `environments/dev/main.tf`. Run `terraform plan` and
   confirm only a task-definition revision and the service update appear.
2. **Gate 1 / gate 2 scope for DEV.** Consent is enabled in the prepared change, ingest is not. If
   DEV holds no real customer data, both conditions are moot and ingest can go on too.
3. **Local stub mode off** — confirm the mobile team does not depend on the fixtures.
4. **The 400-before-gate ordering still affects three other endpoints.** `PUT /budget`, the consent
   grant and the ingest batch all check their flags inside the controller, after `@Valid`. With any
   of those flags — or the master switch — off, a malformed body returns 400 rather than 501/404.
   Pre-existing R1–R6 behaviour; the interceptor generalises to all four in about ten lines. Not
   changed on my own initiative before a Gate 3 review.
5. **A JaCoCo threshold.** Baseline below. Pick a number and I will wire `check` to fail the build.
6. **An NVD API key**, or a different dependency-security mechanism.
7. **`GATEWAY_AUTH_ENABLED=true` is a dead variable on the DEV task definition.** Nothing maps it
   to the property the application reads. Harmless while both say true; misleading the day someone
   sets it false. Wire it or drop it.
8. **A connection-pool problem in DEV**, found in CloudWatch and unrelated to this work: repeated
   `HikariPool-1 - Failed to validate connection … This connection has been closed`, and one health
   check that took **10.2 seconds**. That check is one timeout adjustment from failing, and a
   failing health check pulls tasks from the target group. Worth its own ticket.

---

## G. Deferred to R7 implementation

- **Database-level integration tests.** MockMvc only for now, by agreement. H2 will not reproduce
  the partial unique indexes or PostgreSQL's NULL semantics — which is exactly what R6's
  exactly-once alert rule depends on — so Testcontainers is the right answer once Docker is
  confirmed.
- **Spotless, Checkstyle, SpotBugs, PMD.** Deliberately not added: Spotless in check mode would
  redden the build across a codebase never formatted, and in apply mode would bury this security
  work in an unreviewable diff. They belong in their own commit.
- **BI-27 to BI-36.** Not started, as instructed.

---

## H. Final build and quality-gate result

```
Tests run: 212, Failures: 0, Errors: 0, Skipped: 2      (Surefire)
Tests run: 45,  Failures: 0, Errors: 0, Skipped: 0      (Failsafe)
BUILD SUCCESS
```

**JaCoCo baseline, unit and integration merged, 80 classes:**

| Metric | Coverage |
| --- | --- |
| Instruction | **72.4%** (8,541 / 11,793) |
| Line | **72.3%** (1,901 / 2,631) |
| Method | **76.2%** (275 / 361) |
| Branch | **61.2%** (701 / 1,145) |
| Complexity | **56.9%** (532 / 935) |

By package, and this is where the number is useful rather than flattering:

| Package | Line coverage |
| --- | --- |
| `service.calc` | 97.3% |
| `service.impl` | 90.1% |
| `service.ingest` | 86.1% |
| `config` | 81.4% |
| `exception` | 51.0% |
| `domain` | 41.4% |
| `controller` | 38.0% |
| `service` (interfaces + fixtures) | 13.0% |

Least covered: `InsightsIngestController` **0%**, `InsightsConsentController` **0%**,
`InsightsComplianceServiceImpl` **2.7%**, `InsightsStubFixtures` 5.8%, `OriginVerifyFilter` 11.1%,
`GlobalExceptionHandler` 31.0%.

Two of those matter. **Both zero-coverage controllers are the R3 and R4 surfaces — the two behind
Compliance gates**, and the compliance event service that records what they do sits at 2.7%. The
branch figure of 61.2% is the honest one to quote: over a third of the decisions in this service
have never been executed by a test.

**Dependency-check has not run.** It is configured to fail the build at CVSS 7.0 with no
suppression file, inside a profile activated by `NVD_API_KEY`. No key, no scan — that is a gap, not
a pass.

---

## Not release-ready

The build is green and that is not the same thing. Outstanding against the DIP:

- **Compliance gate 2 cannot pass** without the D-1 corpus.
- **Masked-card approval** is outstanding, and blocks two of the DIP's own demos.
- **R7 has not started** — BI-27 export, BI-28 erasure, BI-29 cache and refresh, BI-30 TTL purge,
  BI-31 OpenAPI polish, BI-34 authorisation suite, BI-35 golden files, BI-36 UAT support. Gate 3
  sits at the end of it.
- **No environment has ever run this feature switched on.** DEV has `enabled=false` and always has.
- **The deployed DEV image is built from a commit not in this branch**, so nothing in R1–R6 as
  written here has run anywhere but a test.
- **Dependency vulnerabilities are unknown**, not absent.
- **Branch coverage is 61.2%**, with the two gate-carrying controllers at zero.

Nothing has been committed or pushed. `MoiPay-BudgetInsights` is at `8ddeccc` with 29 changed or
new paths; `MoiPay-Infrastructure` is at `b1b4417` with one.
