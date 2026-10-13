# R7 Phase 1 — the scheduling foundation

Budget Insights runs `desired_count = 2` in every environment. Spring's `@Scheduled` is per-JVM,
so without a control both tasks fire every job at the same moment against the same database. For
BI-30 that is a double delete; for BI-29 it is two writers racing over the same cache rows. Neither
throws, which is why this ships before the jobs that need it rather than alongside them.

## What the DIP asked for, and why it is not what was built

DIP §23 names ShedLock, and gives `AmlOutboxRelayScheduler` as the platform precedent to copy.
Neither exists:

| Claim | Checked | Result |
| --- | --- | --- |
| ShedLock is the house pattern | every `pom.xml` under `E:\Rubaai Technology` | no service declares a ShedLock dependency |
| `AmlOutboxRelayScheduler` is the precedent | `MoiPay_BackEnd/src/main/java` | the class does not exist |
| Spring services run scheduled jobs | `@Scheduled` across all services | zero occurrences |

Every recurring job on the platform today is an EventBridge schedule driving a Lambda — the
environment start/stop scheduler, the origin-verify rotation, the MQ rotation — outside the Spring
services entirely. So there was no in-service pattern to follow, and the choice was open.

A PostgreSQL advisory lock was taken instead of adding ShedLock. It is the same guarantee with no
new dependency, no new table and no new Flyway migration; the database already in every request
path is the one arbitrating, so there is no second source of truth to keep consistent.

## What was built

| File | Role |
| --- | --- |
| `service/jobs/JobLock.java` | `boolean runExclusively(String jobName, Runnable work)` |
| `service/jobs/PostgresAdvisoryJobLock.java` | `pg_try_advisory_xact_lock`, `REQUIRES_NEW` |
| `service/jobs/SingleInstanceJobLock.java` | H2 fallback; excludes within one JVM only |
| `config/JobLockConfig.java` | picks one by reading the connected database's product name |

No `@EnableScheduling` and no `@Scheduled` method yet — deliberately. The plan's Phase 1 described
a throwaway job whose only purpose was to prove the lock. That job would have been dead
configuration in production from the day it merged, and the proof below is stronger than anything
it could have demonstrated. Scheduling switches on with BI-30 in Phase 6, which is the first job
that has real work to do.

### The decisions worth recording

**Transaction-scoped, not session-scoped.** A session lock is released by `pg_advisory_unlock` or
when the connection closes. Behind HikariCP the connection does not close, it returns to the pool —
so a job that throws before unlocking leaves the lock held by a pooled connection handed to
unrelated work, and the job never runs again until the service restarts. This is proven below, and
it is the single reason for the choice. The consequence is that the job body runs inside the lock's
transaction, which suits a bounded delete and a cache rebuild and would be the wrong shape for a
long-running job. That constraint belongs in the review of any job added later.

**The lock refuses to run outside a transaction.** Everything above rests on the `@Transactional`
proxy being in place. Without a transaction the lock is taken and released at statement end,
`pg_try_advisory_xact_lock` answers true to every caller, and both instances run the job believing
they hold it — the exact outcome this class exists to prevent, leaving no trace. A bean constructed
directly, a self-invocation, or proxying switched off would each produce it. `runExclusively` now
checks `TransactionSynchronizationManager.isActualTransactionActive()` first and throws, turning
the one silent failure mode into one the scheduler logs.

**`try`, not a blocking acquire.** The instance that loses should find out at once and go back to
sleep, not queue behind the winner and run the same work a moment later.

**A metadata read that fails stops the service.** The tempting alternative is to fall back to the
single-instance lock: a wrong guess towards PostgreSQL fails every job loudly with a syntax error,
while a wrong guess the other way still runs. That reasoning is backwards. A PostgreSQL deployment
whose metadata read happened to fail — momentary pool exhaustion at startup, a network blip — would
come up permanently unguarded, running every job on both tasks, with one WARN line in a startup log
nobody reads. The availability argument is hollow anyway: the bean depends on the
`EntityManagerFactory`, which Hibernate has already built by reading JDBC metadata over a
connection of its own, so if that succeeded this will, and if it did not the context has already
failed. Refusing to start is the only answer that cannot silently downgrade the guarantee this
class exists to provide.

**The key is the first eight bytes of SHA-256, not `String.hashCode()`.** The key lives in a
database-wide namespace shared with anything else taking advisory locks, so it wants to be wide and
well distributed. The values are pinned as literals in `PostgresAdvisoryJobLockTest`: if the
derivation ever changes, a rolling deploy would have old tasks locking one key and new tasks
another, and for the length of the deploy the job would have no protection at all. Pinning turns
that into a failing build.

## The proof

Run against **PostgreSQL 16.13**, two concurrent sessions, using the key the code actually derives
for `insights-raw-message-purge` (`2321604234852945982`).

### 1. Mutual exclusion, and release on commit

| Step | Session A | Session B |
| --- | --- | --- |
| A opens a transaction and takes the lock | `true` | |
| B tries while A holds it | | `false` *(returns immediately, does not block)* |
| `pg_locks` where `locktype='advisory'` | | `1` — only A's |
| A commits | committed | |
| B tries again | | `true` |

### 2. Release on rollback — the job throws

| Step | Session A | Session B |
| --- | --- | --- |
| A takes `pg_try_advisory_xact_lock` | `true` | |
| A rolls back (what a thrown job does) | rolled back | |
| B tries | | `true` — released |

### 3. The counterfactual: the same rollback with a **session** lock

| Step | Session A | Session B |
| --- | --- | --- |
| A takes `pg_try_advisory_lock` (session-scoped) | `true` | |
| A rolls back | rolled back | |
| B tries | | **`false` — still held** |

This is the leak. The transaction ended, the work is undone, and the lock is still there on a
connection that has gone back to the pool. Every subsequent run of that job is skipped, silently,
until the task restarts.

### 4. The instance dies mid-job

| Step | Session A | Session B |
| --- | --- | --- |
| A still holds the leaked session lock from case 3 | | |
| A's backend terminated (`pg_terminate_backend`) | killed | |
| B tries | | `true` — released by the server |

Case 4 is the one consolation of the session lock and the reason it is easy to be fooled by: a task
that is *killed* does release. A task that merely throws does not.

## Automated tests

| Test | Proves |
| --- | --- |
| `PostgresAdvisoryJobLockTest` (6) | the refused lock does not run the work; the SQL is the `xact` variant; the key is derived and pinned; no transaction means no run |
| `SingleInstanceJobLockTest` (3) | two threads, one execution; the lock survives a throwing job; different names do not exclude |
| `JobLockSelectionIT` (2) | the H2 context selects the fallback, not the advisory lock |

What the mocked tests prove is the branch and the statement, not PostgreSQL's behaviour — the
tables above are where that lives. A mocked test that appeared to prove mutual exclusion would be
worse than no test, so the javadoc on each says which of the two it covers.

The build has no PostgreSQL to point at, so the advisory path is not exercised by Maven at all. The
options were Testcontainers — a new dependency, a Docker requirement on every build machine, for
one test — or the session-level proof above plus the startup log line each branch writes. The
second was taken. If the service ever starts against a non-PostgreSQL database in a deployed
environment, `JobLockConfig` logs a warning naming the product and saying the jobs are unguarded.

## Open

Nothing blocking. Phase 2 (BI-34, the authorisation suite) is next and does not depend on this.
