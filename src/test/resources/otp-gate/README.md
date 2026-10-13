# OTP leakage release gate — corpus (dependency D-1)

`OtpLeakageReleaseGateTest` looks for `bank-corpus.tsv` in this directory. While the file is
absent, its two per-bank tests **abort** — Surefire reports them as skipped with the reason, not
as passed. A green build with those two skipped does not mean the gate is passed.

## Format

Three tab-separated columns, one message field per line:

    bankCode<TAB>LEAK|CLEAN<TAB>value

- `bankCode` — as used in `bi_sms_sender_allowlist.bank_code`.
- `LEAK` — the value contains an OTP, a PIN, or the message that carried one. The guard must trip.
- `CLEAN` — the value is a legitimate parsed field, typically a merchant string. The guard must not.
- `value` — exactly what the device would put in the field, including branch names, store numbers
  and postal codes. Do not clean it up; the false positives that matter come from real punctuation
  and real digits.

Lines that are blank or start with `#` are ignored. Tabs inside `value` are not supported — the
line is split on the first two tabs only, so a tab in the value would be kept as part of it.

## What the corpus has to contain

Every supported bank needs **both** a LEAK case and a CLEAN case. The gate fails if a bank has only
one, because a corpus of only leaks proves the guard is willing to reject and nothing more.

DIP §32: the gate cannot be partially passed for a subset of banks. Adding a bank to the allowlist
without adding it here leaves that bank unevidenced.

## Where the values come from

Real messages, labelled by whoever owns the sample set — not invented ones. Invented fixtures
produce a guard whose accuracy nobody can evidence, which is the exact thing this gate exists to
prevent.

**Do not commit real customer messages.** Use the redacted or synthetic-but-representative set
agreed with Compliance, with any real code digits replaced by other digits of the same length.
