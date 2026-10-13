# Budget Insights — realistic DEV dataset

`bi-dev-seed.sql` fills the **DEV** Budget Insights database with a synthetic dataset that behaves like real usage, so the mobile screens and the Admin Dashboard can be tested end to end. `bi-dev-seed-cleanup.sql` removes it again.

- **DEV only.** It's not a Flyway migration and never runs automatically.
- **Repeatable.** Every run first deletes what it owns, then rebuilds relative to `CURRENT_DATE`. Re-run it any day to move the data window to "now". The same day produces the same dataset: random numbers are seeded per persona.
- **Synthetic only.** No names, phone numbers, NICs, real account or card numbers. Masked tails are random 4 digits. Merchant names are public Sri Lankan businesses, used only as labels.
- **Schema untouched.** It doesn't touch reference data, admin config, releases or `bi_compliance_event`.

## Run it

Connect as in the *Budget Insights DB access runbook* (SSM tunnel on `localhost:5434`), then:

```bash
psql "host=localhost port=5434 dbname=moipay_budget_insights user=moipay_admin sslmode=require" -f bi-dev-seed.sql
```

In DBeaver, open the file and use **Execute script** (Alt+X). It runs in one transaction and takes about 10 seconds. The last statement prints a one-line summary.

To remove the dataset, run `bi-dev-seed-cleanup.sql` the same way.

## What it creates (run on 1 Oct 2026)

| | Rows |
|---|---|
| Personas (users) | 35 — 34 synthetic `5eed0000-0000-4000-8000-0000000000NN` + the shared DEV test login `9a75b369-…f751` |
| Transactions | ≈ 10,230 over 6 months (lookback window), 24 soft-deleted |
| Accounts (bank / card) | 54 across 5 banks (7056, 7083, 7010, 7287, 7463), linked to the live parser templates |
| Monthly budgets | 153 (with a roll-forward chain for about half) |
| Category caps | 229 |
| Alerts | ≈ 40: approaching, exceeded and overall budget exceeded; past months acknowledged |
| Consent rows | 36 (one customer withdrew) |
| Device sync rows | 38: 4 devices still on reference version 0 |
| Audit rows | ≈ 1,750: manual creates, user re-categorisations, deletes |

**Categories come from the live merchant rules.** The same regex rules the categoriser uses are applied in SQL, so the data matches what the app would have produced.

- For example, `CARGILLS FOOD CITY` lands in *Dining Out*, because the `FOOD` rule outranks `SUPERMARKET`.
- KFC, Daraz, Lanka Sathosa, Singer and ATM withdrawals land in *Other*, which gives the Admin "Other share" a realistic 13–19%.

Some rows are user re-categorised, the way customers would do it:

- Food City → Shopping
- temple donations → Gifts
- lunch packets → Dining

## Personas

"Status" is the **current month**. Past months follow each persona's normal pattern.

| # | Profile | Monthly target (LKR) | Status this month | Notes |
|---|---|---|---|---|
| 1 | family | 145,000 | ON_TRACK | Shopping cap approaching |
| 2 | young professional | 95,000 | ON_TRACK | |
| 3 | traveller, high spend | 280,000 | ON_TRACK | iOS, 2 devices |
| 4 | senior | 60,000 | ON_TRACK | Low spend, pharmacy/hospital heavy |
| 5 | commuter | 130,000 | ON_TRACK | Fuel heavy |
| 6 | student | 38,000 | ON_TRACK | Bus, rides, reloads |
| 7 | family | 180,000 | ON_TRACK | iOS, many manual entries |
| 8 | young professional | 110,000 | AHEAD_OF_PACE | Dining cap exceeded, shopping approaching |
| 9 | family | 160,000 | AHEAD_OF_PACE | |
| 10 | traveller | 240,000 | AHEAD_OF_PACE | iOS, 2 devices |
| 11 | commuter | 90,000 | AHEAD_OF_PACE | Transport cap exceeded; stale device (v0) |
| 12 | student | 45,000 | AHEAD_OF_PACE | |
| 13 | young professional | 85,000 | OVER_BUDGET | Dining and entertainment caps exceeded |
| 14 | traveller | 200,000 | OVER_BUDGET | Travel and dining exceeded; iOS, 2 devices |
| 15 | student | 30,000 | OVER_BUDGET | |
| 16 | family | 120,000 | OVER_BUDGET | Stale device |
| 17 | commuter | 75,000 | OVER_BUDGET | |
| 18–20 | traveller / family / senior | — | no budget | High / moderate / low spend |
| 21 | young professional | 50,000 | — | New customer: only 3 transactions |
| 22 | family | 75,000 | ON_TRACK (0 spent) | Consent and budget, **no transactions** |
| 23 | young professional | — | — | Consent only: **empty everything** |
| 24 | family | — | — | **Withdrew consent** 40 days ago; history only |
| 25 | commuter | — | — | **Dormant**: nothing in the last 75 days |
| 26 | young professional | 70,000 | ON_TRACK | Joined 9 days ago |
| 27, 28, 30, 32 | mixed | 40k–320k | ON_TRACK | Volume |
| 29, 34 | family / student | 140k / 35k | AHEAD_OF_PACE | |
| 31 | senior | 55,000 | OVER_BUDGET | Medical bills |
| 33 | young professional | — | no budget | |
| **35** | young professional | 120,000 | **AHEAD_OF_PACE** | **The DEV test login**, so the mobile app shows real data |

What makes the data behave like real usage:

- **Monthly bills on fixed days:**
  - CEB electricity on the 8th, water on the 12th, broadband on the 3rd;
  - Netflix, Spotify, gym, tuition, school fees, loan instalment and card payment;
  - salary credit on the 25th (credits never count as spend).
- **Purchase patterns:** more at weekends, a payday bump, and realistic hours (bakery in the morning, dining at lunch and dinner, fuel during the day).
- **Realistic amounts:**
  - fuel in Rs 100 steps;
  - rides to the rupee;
  - supermarkets to the cent;
  - big-ticket purchases (Abans, Singer, SriLankan, hospitals) only when a persona's story needs them.
- **Fresh data:** there are transactions today for about 30 users, so Today / 7 / 30 / 90 / 180-day filters all show data.

**Day-of-month note.** On the 1st of a month, "this month" holds only today's activity. The OVER_BUDGET and AHEAD personas then show large first-day purchases. Re-run the seed later in the month for smoother current-month numbers. The statuses come out right on any day.

## Check it

```sql
SET search_path TO budget_insights, public;
SELECT count(DISTINCT user_id) users, count(*) txns, min(txn_date), max(txn_date)
FROM bi_transaction WHERE idempotency_key LIKE 'seed-%';
```

Then check through the APIs:

- **Admin Dashboard:** Overview for Today / 7 / 30 / 90 days; category, sender and template usage; merchant-rule match counts.
- **Mobile, with the DEV test login:** summary (AHEAD_OF_PACE), budget, activity, breakdown, transactions, alerts, recommendations and accounts.
