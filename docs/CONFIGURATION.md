# Budget Insights — configuration reference

Every property this service reads, the environment variable behind it, and what it defaults to per
profile. Written during the R7 readiness preflight because the DIP and the code disagreed about
every flag name and nobody had noticed.

---

## The naming decision

**Canonical prefix: `moipay.budget-insights.*`. The code is correct and the DIP is wrong.**

The DIP names every flag `moipay.insights.*` (§24, lines 1626–1738). No such property exists
anywhere in this service, and setting one has no effect.

The code's prefix is the one that matches the platform:

| Service | Prefix |
| --- | --- |
| CaseEngine | `moipay.case-engine.*` |
| SplitEngine | `moipay.split-engine.*` |
| **Budget Insights** | **`moipay.budget-insights.*`** |

Renaming the code to match the DIP would break the convention three sibling services follow and
orphan the `BUDGET_INSIGHTS_*` environment variables the dev task definition already sets. The DIP
is the artefact that changes.

Two properties sit outside this prefix, deliberately, because they are platform-wide and shared
with the sibling services rather than owned by this one:

- `moipay.cors.allowed-origins` — same property name FeeEngine and CaseEngine use.
- `moipay.security.origin-verify.*` — same property names SplitEngine uses.

### DIP corrections required

| DIP line | DIP says | Actual property |
| --- | --- | --- |
| 1626 | `moipay.insights.stub.enabled` | `moipay.budget-insights.stub.enabled` |
| 1648 | `moipay.insights.manual.enabled` | `moipay.budget-insights.manual.enabled` |
| 1661 | `moipay.insights.budget.enabled` | `moipay.budget-insights.budget.enabled` |
| 1674 | `moipay.insights.consent.enabled` | `moipay.budget-insights.consent.enabled` |
| 1688 | `moipay.insights.ingest.enabled` | `moipay.budget-insights.ingest.enabled` |
| 1710 | `moipay.insights.activity.enabled` | `moipay.budget-insights.activity.enabled` |
| 1724 | `moipay.insights.alerts.enabled` | `moipay.budget-insights.alerts.enabled` **and** `…recommendations.enabled` — see the deviation note below |
| 1738 | `moipay.insights.enabled` | `moipay.budget-insights.enabled` |

One occurrence had already leaked into running code: `InsightsComplianceServiceImpl` logged
"`moipay.insights.device-hash-salt` is not set", telling an operator to set a property that does
not exist. Corrected.

---

## Release flags

All default **off**. The feature ships dark and is enabled per environment.

| Release | Property (under `moipay.budget-insights.`) | Environment variable | Off behaviour |
| --- | --- | --- | --- |
| master | `enabled` | `BUDGET_INSIGHTS_ENABLED` | 404 — the tab looks absent |
| R1 | `manual.enabled` | `BUDGET_INSIGHTS_MANUAL_ENABLED` | 501 on the three write routes; reads untouched |
| R2 | `budget.enabled` | `BUDGET_INSIGHTS_BUDGET_ENABLED` | 501 |
| R3 | `consent.enabled` | `BUDGET_INSIGHTS_CONSENT_ENABLED` | 501 |
| R4 | `ingest.enabled` | `BUDGET_INSIGHTS_INGEST_ENABLED` | 501 |
| R5 | `activity.enabled` | `BUDGET_INSIGHTS_ACTIVITY_ENABLED` | 501 |
| R6 | `alerts.enabled` | `BUDGET_INSIGHTS_ALERTS_ENABLED` | 501 |
| R6 | `recommendations.enabled` | `BUDGET_INSIGHTS_RECOMMENDATIONS_ENABLED` | 501 |
| — | `stub.enabled` | `BUDGET_INSIGHTS_STUB_ENABLED` | fixtures instead of live data |

404 versus 501 is not cosmetic. The master switch answering 404 means the Insights tab is absent;
a release flag answering 501 means the tab is present and one capability is withdrawn.

### Deployment gap

`environments/dev/main.tf` sets only `BUDGET_INSIGHTS_ENABLED` and `BUDGET_INSIGHTS_STUB_ENABLED`
in `extra_environment`. **None of the seven per-release flags are wired**, so flipping the master
switch today turns on nothing. Adding them is an infrastructure change and is out of scope for this
repository.

### BI-36 has no release flag

`POST /api/insights/compliance/events` is deliberately absent from the table above. It records that
a handset detected and destroyed an OTP or PIN — a control that is already running on every device
whether or not this service is listening. A flag would only buy the option of running the control
and not recording it, which is the state the route exists to end.

What withholds it instead is `BUDGET_INSIGHTS_DEVICE_HASH_SALT`. With no salt the client reference
cannot be stored as a digest the client could not reverse, and `bi_compliance_event` is never
purged — so the route answers **501** and the device holds its queue. Setting the salt starts the
recording: no release, no task-definition change, no client change. `ComplianceReportingGuard` logs
at ERROR on every boot where Budget Insights is enabled and the salt is blank, because "the
handsets are filtering and nothing is being recorded" is a compliance gap worth alerting on.

The route still sits under the master switch, which answers 404 as it does for everything else.

**Know what the salt costs before using it as a switch.** It is now doing three jobs at once: it is
the secret, it is what makes BI-36 reporting available, and it keys that route's duplicate
suppression. They are coupled, and the coupling has teeth:

- Blanking it to withhold BI-36 also sets `device_id_hash` to null on **every** compliance event the
  server raises — ingest OTP detections, sender rejections, consent changes, category overrides,
  export and erasure — for as long as it is blank. Those rows are never purged and never backfilled,
  so that correlation is lost permanently for the window.
- Rotating it for security reasons ends duplicate suppression for reports in flight: digests written
  before the rotation no longer match, so a retry across a rotation is recorded twice.

There is therefore **no per-route rollback** for BI-36. That is the deliberate cost of having no
release flag. If a rollback for this route alone is ever needed, add a separate
`compliance.client-ref-salt` defaulting to `device-hash-salt` rather than blanking the shared one.

**Deploy ordering.** `V1.4.0` adds `bi_compliance_event.client_ref_hash`, and the entity maps it, so
every compliance write — including the seven the server raises for itself — names that column from
this release onwards. Flyway runs at startup (`SPRING_FLYWAY_ENABLED`, default `true`) and applies
the migration in the same boot, so an ordinary deployment is safe. Deploying this release with
Flyway disabled against a database that has not had `V1.4.0` applied is not: every compliance write
would fail, and `InsightsComplianceServiceImpl.raise` swallows its failures by design, so the only
symptom would be an ERROR log line.

---

## Tunables

| Property | Environment variable | Default |
| --- | --- | --- |
| `lookback-months` | `BUDGET_INSIGHTS_LOOKBACK_MONTHS` | 6 — a Compliance position, not an implementation detail |
| `currency` | `BUDGET_INSIGHTS_CURRENCY` | LKR |
| `zone` | `BUDGET_INSIGHTS_ZONE` | Asia/Colombo |
| `pace-tolerance-pct` | `BUDGET_INSIGHTS_PACE_TOLERANCE_PCT` | 0 |
| `device-hash-salt` | `BUDGET_INSIGHTS_DEVICE_HASH_SALT` | *(secret)* blank disables device correlation on compliance events, **and makes BI-36 reporting answer 501** |
| `masked-card-to-backend-enabled` | *(YAML only)* | false — pending Compliance sign-off |
| `consent.version` | `BUDGET_INSIGHTS_CONSENT_VERSION` | 1.0 — raising it invalidates every existing consent |
| `ingest.max-batch-size` | `BUDGET_INSIGHTS_INGEST_MAX_BATCH` | 200 |
| `ingest.rate-limit-messages` | `BUDGET_INSIGHTS_INGEST_RATE` | 600 |
| `compliance.max-batch-size` | `BUDGET_INSIGHTS_COMPLIANCE_MAX_BATCH` | 200 — BI-36 batch bound. **aws-dev and local only**; `application-aws-staging.yml` has no `compliance:` block, so this variable is ignored there until one is added |
| `compliance.rate-limit-messages` | `BUDGET_INSIGHTS_COMPLIANCE_RATE` | 600 — **aws-dev and local only**, as above |
| `compliance.clock-skew-minutes` | *(YAML only)* | 10 — how far ahead of the server a handset's clock may be before its report is refused |
| `recommendations.needs/wants/savings` | *(YAML only)* | the approved 50/30/20 mapping |

---

## Security

| Property | Environment variable | Notes |
| --- | --- | --- |
| `moipay.budget-insights.admin.username` | `BUDGET_INSIGHTS_ADMIN_USERNAME` | **No default.** Unset means an account with an unrecorded random password that cannot be authenticated as |
| `moipay.budget-insights.admin.password` | `BUDGET_INSIGHTS_ADMIN_PASSWORD` | **No default**, as above |
| `moipay.budget-insights.security.gateway-auth-enabled` | *(YAML per profile)* | true in every AWS profile |
| `moipay.budget-insights.security.gateway-internal-token` | `GATEWAY_INTERNAL_TOKEN` | **No default.** Blank means a request carrying a token is passed through unauthenticated and the misconfiguration is logged |
| `moipay.cors.allowed-origins` | `CORS_ALLOWED_ORIGINS` | Localhost only by default. Real origins belong on the task definition, not in this repository |
| `moipay.security.origin-verify.enabled` | `ORIGIN_VERIFY_ENABLED` | Terraform sets this on every task; the staging profile had no wiring to read it until now |
| `moipay.security.origin-verify.enforce` | `ORIGIN_VERIFY_ENFORCE` | false is log-only |
| `moipay.security.origin-verify.secret` | `ORIGIN_VERIFY_SECRET` | *(secret)* |

The admin account applies only when `gateway-auth-enabled` is false. Every AWS profile runs with it
true, where HTTP Basic is not configured at all.

**CORS applies to browsers only.** The mobile app is a native client, sends no `Origin` header, and
is unaffected by the allowlist.

---

## Per-profile defaults

| | base | local | aws-dev | aws-staging |
| --- | --- | --- | --- | --- |
| master and all release flags | off | **on** | env, off | env, off |
| `stub.enabled` | off | **off** — see below | env, off | env, off |
| `gateway-auth-enabled` | — | false | true | true |
| Flyway `out-of-order` | — | Flyway off | **true** | **false** |
| Swagger UI | — | — | **true** | **false** |
| origin-verify | — | — | env, off | env, off |

### Local stub mode

`stub.enabled` was `true` locally and is now `false`. Stub mode exists so the frontend could
integrate against the published contract before the logic behind it existed; for R1–R6 that logic
now exists. Leaving it on gave local a split personality — `/summary`, `/breakdown` and `/activity`
answered from fixtures while `/alerts` and `/recommendations`, which never had fixtures, answered
from the database. A developer was looking at two systems through one screen with no way to tell
which. Set it back to `true` to get the fixture set again; the guard still permits it on this
profile.

### Flyway out-of-order

Dev keeps `true` — it is iterative by intent and its database was built by `ddl-auto` before Flyway
arrived. Staging now defaults `false`: it rehearses the production promotion, and a migration that
lands out of sequence on the rehearsal proves nothing about the real one. Safe to flip today —
migrations run V1.0.0 → V1.1.0 strictly ascending, and Budget Insights has no staging deployment
(`environments/staging/main.tf` has no `budget_insights` module), so there is no history to
conflict with.

### Swagger

Dev keeps it on; the frontend and mobile teams integrate against it there. Staging now defaults
off — the existing comment said "set to false before production promotion", and a checklist item
that has to be remembered is one that eventually is not. Set `SWAGGER_ENABLED=true` on the task
definition when it is genuinely needed.

---

## The R6 two-flag deviation — requires sign-off

The DIP gives R6 a single flag, `alerts.enabled` (line 1724). The implementation ships two:
`alerts.enabled` and `recommendations.enabled`.

This is deliberate and is **not** the same thing as the prefix mismatch above. The prefix mismatch
is a documentation error to be corrected. This is a design decision to be accepted or rejected:

- The two features fail independently. Alert evaluation runs inside the transaction of every
  write path; recommendations is a read-time calculation that touches nothing. A defect in one is
  no reason to withdraw the other.
- They roll back independently. With one flag, pulling a recommendation-panel defect also silences
  every budget alert a customer is relying on.
- A recommendations panel served behind a flag named "alerts" is a flag nobody reads correctly at
  three in the morning.

Affected references: DIP §24 line 1724 (the R6 flag row) and §25 (the R6 task row, which names
BI-25 and BI-26 as one release). To reject the deviation, delete `Recommendations.enabled` from
`InsightsProperties`, gate `/recommendations` on `alerts.enabled` in `InsightsController`, and
remove the `recommendations.enabled` key from all four profiles.
