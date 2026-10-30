#!/usr/bin/env bash
# Starts the backend on this Mac against the local PostgreSQL database (local profile).
#
# Works from any folder and picks Java 21 itself, whatever JAVA_HOME your shell sets:
#   ./scripts/run-local.sh
# Stop it with Ctrl+C. The API is then at http://localhost:8087.
set -euo pipefail

cd "$(dirname "$0")/.."

# Local-only settings that aren't committed (e.g. SUPER_ADMIN_PHONES=+9477XXXXXXX for the admin dashboard).
if [[ -f .env.local ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env.local
  set +a
fi

JAVA21=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
if [[ ! -x "$JAVA21/bin/java" ]]; then
  # java_home -v 21 means "21 or newer", so check it really is 21.
  JAVA21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  [[ -n "$JAVA21" && "$("$JAVA21/bin/java" -version 2>&1)" == *\"21.* ]] || JAVA21=
fi
if [[ -z "$JAVA21" ]]; then
  echo "Java 21 not found. Install it with: brew install openjdk@21" >&2
  exit 1
fi
export JAVA_HOME="$JAVA21"
export PATH="$JAVA_HOME/bin:$PATH"

if lsof -ti tcp:8087 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port 8087 is already in use: the backend is probably running already." >&2
  echo "Stop it with: kill \$(lsof -ti tcp:8087)" >&2
  exit 1
fi

exec mvn -B spring-boot:run -Dspring-boot.run.profiles=local
