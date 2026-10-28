# MoneyReserve API

The backend for the MoneyReserve app: sign-in with a mobile number and SMS code, and Budget
Insights — spending read from bank SMS on the phone, categories, manual entries, monthly budgets,
alerts, consent and data export/erasure. Also an admin API for managing banks, SMS senders,
parser templates, merchant rules and categories.

    MoneyReserve app → this API → PostgreSQL (database `moneyreserve`, schema `budget_insights`)

Java 21, Spring Boot 3.4, Maven, Flyway.

| | |
|---|---|
| Port | 8087 |
| Database | `moneyreserve` (schema `budget_insights`, created by Flyway) |
| Config prefix | `moneyreserve.*` |
| API docs | `/swagger-ui.html` (spec at `/v3/api-docs`) |

## Run it locally

1. **Database (once).** Creates user `moneyreserve` (password `moneyreserve`) and database
   `moneyreserve` on your local PostgreSQL:

       ./scripts/create-local-db.sh

2. **Start the API** with the `local` profile. Flyway creates and migrates the tables on start-up.
   The script picks Java 21 itself and works from any folder:

       ./scripts/run-local.sh

3. **Point the app at it.** In the Expo app's `.env.local`:
   `EXPO_PUBLIC_API_URL=http://localhost:8087` for Expo web on this Mac, or
   `http://<this Mac's LAN IP>:8087` for a phone or emulator.

On the local profile no SMS is sent: every request gets a fresh random 6-digit code, printed in the
backend terminal as a `SIGN-IN CODE` banner. `+94 77 000 0000` signs in as a super admin (`moneyreserve.auth.super-admin-phones`).

## Sign-in

| Route | Body | Result |
|---|---|---|
| `POST /api/auth/register` | `{fullName, phone, email?}` | texts a 6-digit code |
| `POST /api/auth/login` | `{phone}` | texts a 6-digit code |
| `POST /api/auth/verify` | `{phone, code, purpose}` | `{accessToken, refreshToken, expiresIn, user}` |
| `POST /api/auth/refresh` | `{refreshToken}` | a new token pair (the old refresh token stops working) |
| `POST /api/auth/logout` | `{refreshToken}` | ends that session |
| `GET /api/auth/me` | — | the signed-in user |
| `DELETE /api/auth/account` | — | erases the user's Budget Insights data and deletes the account |

Every other route needs `Authorization: Bearer <accessToken>`. The access token lasts 15 minutes;
refresh tokens last 30 days and rotate on each use (reusing an old one ends the whole session).
Users are `CUSTOMER` by default; numbers listed in `moneyreserve.auth.super-admin-phones` get
`SUPER_ADMIN`, which the admin API (`/api/insights/admin/**`) requires.

Error bodies carry a code such as `MR.AUT.E.004`; see the API docs for the full list.

## Configuration

`src/main/resources/application.yml` holds everything, with secrets from environment variables;
`application-local.yml` adds development defaults.

| Variable | Used for |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection |
| `BI_JWT_SECRET` | signing access tokens (32+ bytes; required outside `local`) |
| `SUPER_ADMIN_PHONES` | comma-separated admin numbers |
| `CORS_ALLOWED_ORIGINS` | browser origins (Expo web) |
| `DEVICE_HASH_SALT` | hashing device ids on the compliance route |

Sign-in currently only has a log-only SMS sender, so it refuses to start outside the `local` and
`test` profiles until a real SMS provider is added.

## Tests

    mvn verify

Unit tests run under Surefire, integration tests (`*IT`) under Failsafe. Tests use an in-memory
H2 database, so they need no PostgreSQL. Integration tests sign in with real access tokens through
`TestTokens`.
