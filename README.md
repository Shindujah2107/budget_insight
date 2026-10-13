# MoiPay Budget Insights

SMS-derived spending analysis: categorisation, manual entry and correction, spending
breakdown, and (from R3) monthly budgets and alerts.

    Mobile → API Gateway → Budget Insights → budget_insights DB

Sibling of MoiPay-CaseEngine, MoiPay-FeeEngine and MoiPay-SplitEngine, and built to the
same shape: Java 21, Spring Boot 3.4.1, `com.moipay.*`, gateway-injected identity, its own
database and schema, Flyway from `db/migration/common`.

| | |
|---|---|
| Local port | 8087 |
| ALB route | `/insights/*` |
| Database / schema | `moipay_budget_insights` / `budget_insights` |
| Config prefix | `moipay.budget-insights.*` |
| OpenAPI | `/insights/v3/api-docs/openapi` |

## Migrated out of MoiPay-Backend

R0 and R1 were built inside `MoiPay-Backend/core` and committed there as `bdd15510`
("MIP-8 | Budgetting Insights & Analytics-foundation"). This service is that code, with
the same behaviour and the same HTTP contract. Four things had to change, and each is a
consequence of the service owning its own database and sitting behind the gateway.

**Relations are UUIDs.** Core modelled every association as a JPA `@ManyToOne`, including
`BiTransaction → User`. `user` and `tenant` live in another service's database now, so
those could not survive; the intra-feature ones were converted too, because the sibling
services model relations that way and a half-and-half entity model is worse than either.
`InsightsMapper.toDto` therefore takes the account as an argument, and the query service
loads a page of accounts in one query rather than firing a lazy proxy per row.

**Twelve foreign keys were dropped.** Seven into `moipay_core."user"(uid)` and five into
`moipay_core.tenant(tenant_id)`. The columns are still `NOT NULL` and still indexed, but
the database can no longer prove they point at a real user. The gateway does, by only ever
forwarding claims it validated. The nine foreign keys *between* Budget Insights tables are
unchanged and still enforced.

**There are no scopes.** Core guarded every endpoint with `SCOPE_INSIGHTS_READ` or
`SCOPE_INSIGHTS_TXN_WRITE`, resolved by querying its `rbac` schema. This service has no
such schema and no way to resolve a scope, so — as in all three sibling services — the
control is ownership scoping inside every repository query. Every finder here takes the
owner; none can be called without one. `V2.19.2__budget_insights_scopes.sql` stays in core
for the gateway's use.

**One error body changed.** A request that is authenticated but carries no customer
identity answered with core's `AUTH_UNAUTHORIZED`; that code belongs to core's auth
surface and did not move. It now answers 401 with `error: "Unauthorized"`. Every one of
the twelve `MOI.BI.*` codes is unchanged, and so is the `ApiResponse` success envelope.

## Running locally

    mvn spring-boot:run -Dspring-boot.run.profiles=local

H2 in memory, Hibernate creates the schema, the feature is on and serving **fixture
data** — `moipay.budget-insights.stub.enabled=true`. `InsightsStubModeGuard` refuses to
start the service if that is ever true outside `local`, `test`, `aws-dev` or
`aws-staging`, because fixture spending data reaching UAT sign-off would be worse than the
feature being unavailable.

Swagger: <http://localhost:8087/swagger-ui.html> (basic auth, `admin` / `admin123`).

## Scope

R0 (contract and stub) and R1 (categorisation, manual transactions, breakdown) only.
R2 onward — consent, on-device capture, ingest, the OTP compliance path, budgets, the
aggregate cache read path — is designed but not built. The entities and enums those
releases need are present because they were part of the R0 schema; the services are not.
