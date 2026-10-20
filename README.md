# My Expenses

My Expenses is a private, Android-first expense tracker for personal use in India. It turns
transactional SMS and selected Gmail messages into a searchable local ledger, while keeping raw
message bodies out of the database.

This is an independent implementation. It does not use Axio code, branding, bank credentials,
UPI PINs, OTPs, payment APIs, lending features, or RBI Account Aggregator access.

## What works

- Automatic scan of existing transactional SMS and capture of new alerts
- Read-only Gmail import using a user-owned Google Cloud OAuth client
- Indian rupee amounts, debit/credit/refund/reversal/failed-payment parsing
- Merchant/category suggestions with editable categories and parser confidence
- Monthly overview, category and merchant breakdowns, searchable review queue, and budgets
- In-app budget alerts plus editable and archivable local accounts
- Manual entries and corrections
- Cross-source deduplication using references plus amount, time, account hint, and merchant
- CSV import/export
- Password-encrypted backup and restore
- SQLCipher ledger encryption with a random key protected by Android Keystore
- No backend, ads, analytics, or automatic cloud backup

## Privacy model

Raw SMS and Gmail bodies are read only long enough to extract a normalized transaction. The
database stores amount, type, merchant, category, timestamp, source, confidence, optional account
suffix, persisted parser rules, and one-way source fingerprints. OTP/authentication messages are
rejected. SMS sender and transaction-keyword filters run before normalization.

The app disables Android cloud backup and device-to-device transfer. The database is encrypted
with SQLCipher. Exported CSV files are intentionally readable; encrypted `.myexpenses` backups use
AES-256-GCM with a PBKDF2-derived key.

Do not add real SMS, email, statements, OAuth tokens, keystores, or financial exports to this
repository.

## Requirements

- Android Studio Rabbit 1 (2026.2.1) or newer, or a command-line Android SDK
- JDK 17 or newer
- Android SDK 37 and Build Tools 36.0.0+
- An Android 8.0+ device; Android 13+ is recommended
- Google Play services for Gmail authorization

## Download the APK

Download the private debug build from
[`releases/my-expenses-1.0.0-debug.apk`](releases/my-expenses-1.0.0-debug.apk).
Android may ask you to allow installation from your browser or file manager before sideloading it.

## Build and run

1. Install Android SDK 37 and accept the Android SDK licenses.
2. Point `ANDROID_HOME` at your SDK, or create an uncommitted `local.properties`:

   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   ```

3. Build:

   ```bash
   ./gradlew assembleDebug
   ```

4. Install the resulting APK:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

The first launch explains why SMS access is requested. You can decline it and use manual entries,
CSV import, and Gmail independently.

## Gmail setup

Gmail access is optional. It uses Google's restricted `gmail.readonly` scope and processes messages
on-device.

1. Create a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable the Gmail API.
3. Configure the OAuth consent screen as **External** and add your Gmail address under **Test
   users**.
4. Add the `https://www.googleapis.com/auth/gmail.readonly` scope.
5. Create an **Android OAuth client** with package name `com.myexpenses`.
6. Get the signing SHA-1:

   ```bash
   ./gradlew signingReport
   ```

7. Add the debug certificate SHA-1 to the Android OAuth client, install the app, then choose
   **Connect Gmail** in Settings.

No client secret belongs in an Android app, and this project does not require one. In Google OAuth
testing mode, only listed test users can connect. A public release or use beyond a small private
test group can require Google's restricted-scope verification. If Gmail data is ever sent to a
server, Google may also require a security assessment.

The default Gmail filter searches one year of messages with common transaction subjects. For
better privacy and accuracy, replace it in Settings with explicit senders, for example:

```text
newer_than:1y {from:alerts@yourbank.example from:cards@yourbank.example}
```

Background Gmail sync runs about every six hours when Android permits it. Manual **Sync now**
always requests a fresh cached access token. If consent expires, the app asks you to reconnect.

## Parser behavior

The parser deliberately favors false negatives over turning OTPs or personal messages into
transactions. Indian banks change SMS and email templates without notice, so every imported item
shows a confidence score and remains editable. Low-confidence and non-posted imports appear in the
**Needs review** filter.

Parser templates are persisted in the encrypted `parser_rules` table. The app seeds generic UPI,
card, and bank debit/credit rules. A rule can constrain the sender/body with regular expressions and
optionally override merchant extraction or category assignment. To add a bank template:

1. Add a fully synthetic message to `TransactionParserTest`.
2. Add or seed a narrowly scoped `ParserRuleEntity`; never use a real sender, account, reference, or
   merchant history.
3. Run the parser and repository tests, then verify the message on an emulator.

Synthetic parser fixtures live in:

```text
app/src/test/java/com/myexpenses/ingestion/TransactionParserTest.kt
```

They cover SMS/email debits, credits, same-reference duplicates, reversals, transfers, failed and
malformed payments, custom parser rules, keyword filtering, and OTP rejection. Instrumented
repository tests exercise the local-ledger schema, parser-rule seeding, account lifecycle, and
cross-source deduplication. Never paste an actual account number, UTR, email, phone number, or
merchant history into a test fixture.

## Tests

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
```

Instrumented tests require an emulator or device. SMS can be exercised on an emulator without real
financial data:

```bash
adb emu sms send VM-HDFCBK "Rs.249.00 debited from a/c XX4821 and paid to TEST CAFE."
```

## Known limits

- iOS does not expose the SMS inbox, so this implementation is Android-only.
- SMS/email are notification channels, not bank ledgers. Missing alerts, delayed email, duplicate
  notices, reversals, and bank template changes are unavoidable.
- Direct UPI history and retail bank feeds are not open consumer APIs. RBI Account Aggregator data
  cannot be used as an unrestricted personal-app API.
- OEM battery controls can delay receivers and WorkManager. A manual rescan is available.
- The generic parser cannot identify every own-account transfer or credit-card repayment. Review
  these transactions and exclude duplicates from totals.
- Budget alerts are visible inside the app; this private build does not post financial details as
  Android notifications.
- Google Play distribution with `READ_SMS`/`RECEIVE_SMS` requires a sensitive-permission
  declaration and approval for the SMS-based money-management exception. This private build is
  intended for sideloading first.
- Family profiles and shared synchronization are intentionally not implemented. Export an
  encrypted backup before changing devices.

## Project structure

```text
app/src/main/java/com/myexpenses/
├── backup/             # CSV and encrypted portable backups
├── data/               # Room entities, DAOs, repository, preferences
├── ingestion/email/    # Gmail API client and WorkManager sync
├── ingestion/sms/      # Inbox scan and SMS receiver
├── security/           # Android Keystore-wrapped SQLCipher key
└── ui/                 # Compose UI, state, and Android authorization launchers
```
