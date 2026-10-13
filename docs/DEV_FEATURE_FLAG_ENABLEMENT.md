# Enabling R1–R6 in AWS DEV

Prepared during the R7 readiness work. **Not applied.** Enabling DEV cannot be done from this
repository, and the reason is worth reading before anyone tries.

---

## Why the YAML defaults are not enough

Every flag in `application-aws-dev.yml` has the shape:

```yaml
enabled: ${BUDGET_INSIGHTS_BUDGET_ENABLED:false}
```

The value after the colon is a **fallback used only when the environment variable is absent**. When
the container has the variable set, the variable wins. So the effective value in DEV depends on the
task definition, not on this file.

`MoiPay-Infrastructure/environments/dev/main.tf` (the `budget_insights` module, around line 677)
currently sets exactly two:

```hcl
extra_environment = {
  BUDGET_INSIGHTS_ENABLED      = "false"
  BUDGET_INSIGHTS_STUB_ENABLED = "false"
}
```

`modules/microservice/main.tf:490` folds `extra_environment` into the container's `environment`
list, so those two are real container variables and override whatever the YAML says.

Two consequences:

1. **The master switch is pinned off.** Changing the YAML default to `true` would not enable
   Budget Insights in DEV. `BUDGET_INSIGHTS_ENABLED=false` is set on the container and wins.
2. **The other seven flags are not set at all**, so their YAML defaults do apply — but with the
   master switch off, every endpoint answers 404 regardless. Flipping the seven without the master
   changes nothing observable.

Editing the YAML defaults to `true` is also the wrong instrument. `application.yml` states the
posture the whole configuration is built on — "Everything below ships OFF. The feature is enabled
per environment, never by default" — and a profile whose defaults are `true` hands any future
environment built from it a feature that is already on. The flags belong on the task definition,
explicitly, where they are auditable and reversible without a code deploy. The Terraform comment
above `extra_environment` says the same thing: *"Enabling it is a Compliance decision, taken on the
task definition, not here."*

---

## The change

In `MoiPay-Infrastructure/environments/dev/main.tf`, module `budget_insights`:

```hcl
  extra_environment = {
    BUDGET_INSIGHTS_ENABLED                 = "true"
    BUDGET_INSIGHTS_MANUAL_ENABLED          = "true"
    BUDGET_INSIGHTS_BUDGET_ENABLED          = "true"
    BUDGET_INSIGHTS_CONSENT_ENABLED         = "true"
    BUDGET_INSIGHTS_INGEST_ENABLED          = "true"
    BUDGET_INSIGHTS_ACTIVITY_ENABLED        = "true"
    BUDGET_INSIGHTS_ALERTS_ENABLED          = "true"
    BUDGET_INSIGHTS_RECOMMENDATIONS_ENABLED = "true"
    BUDGET_INSIGHTS_STUB_ENABLED            = "false"
  }
```

`STUB_ENABLED` stays `false` deliberately. With the release flags on, stub mode would serve
fixtures from the endpoints that have them and live data from the ones that do not — the same split
personality that was just removed from the local profile.

Then, with the plan reviewed and no unexpected changes:

```
cd environments/dev
terraform plan -out=budget-insights-flags.tfplan
# review, then
terraform apply budget-insights-flags.tfplan
```

A task-definition revision plus a service redeployment is expected. Nothing else should appear in
the plan; if anything does, stop.

### Without Terraform

The same result can be had by adding the variables to a new task-definition revision in the console
or with `aws ecs register-task-definition` + `update-service`. Faster, but it drifts from Terraform
and the next `apply` reverts it. Fine for an afternoon of testing, not for a state anyone depends
on.

---

## Two Compliance checks before enabling

Both are written into `application-aws-dev.yml` and neither is mine to clear.

- **`BUDGET_INSIGHTS_CONSENT_ENABLED` — Gate 1.** The profile says gate 1 must pass "before this is
  enabled in any environment that can reach real customer data".
- **`BUDGET_INSIGHTS_INGEST_ENABLED` — Gate 2.** The profile says gate 2 must pass — "the OTP
  leakage test green for every supported bank format" — "before this is enabled anywhere that can
  reach real customer messages". **Gate 2 has not passed.** The labelled bank corpus (D-1) is still
  absent, so `OtpLeakageReleaseGateTest` aborts its two per-bank tests on every build.

Whether DEV counts as "an environment that can reach real customer data" is a question for whoever
owns those gates. If DEV holds only synthetic data the conditions do not bite; if a real handset
has ever forwarded a real bank message to it, they do. Enabling ingest in DEV without that answer
would be using a Compliance gate as a formality.

---

## Verification that still has to happen

None of this was confirmed against the running service — the AWS connector was unauthenticated for
the session in which this was written, so the live task definition was never read and no API was
called. After applying:

1. Read the effective environment from the running task, not from Terraform:
   `aws ecs describe-task-definition --task-definition <arn> --query 'taskDefinition.containerDefinitions[0].environment'`
2. Confirm the service reaches a steady state and the target group turns healthy.
3. Check the startup line `Budget Insights: enabled=true, stub=false, lookbackMonths=6,
   currency=LKR` in CloudWatch — `InsightsStubModeGuard` logs it on every boot.
4. Exercise one route per release through the gateway, with the gateway headers:
   - R1 `GET /insights/api/insights/breakdown`, and a `POST /insights/api/insights/transactions`
   - R2 `GET /insights/api/insights/summary`
   - R3 `GET /insights/api/insights/consent`
   - R4 `GET /insights/api/insights/reference/sync`
   - R5 `GET /insights/api/insights/activity`
   - R6 `GET /insights/api/insights/alerts` and `GET /insights/api/insights/recommendations`

   A 404 anywhere means the master switch did not take. A 501 means that release's own flag did
   not.
