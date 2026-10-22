# Rupee Ledger

Private Android money manager for India (`com.ajamuar.ledger`). Rupee Ledger is the only application maintained in this repository. The former My Expenses source and APK have been removed; no app is uninstalled from your phone by this repository change.

The working name is Rupee Ledger. The design/research reference is [the greenfield plan](docs/greenfield-plan.md); [implementation status](docs/implementation-status.md) distinguishes the working release from the full screen inventory.

## Working features

- Overview with Indian-formatted rupee amounts, category spending and drill-down.
- Manual transactions; incoming/outgoing direction separate from expense/income/transfer/card repayment/refund/investment/reimbursement classification.
- Optional SMS account discovery and parsed-alert previews, explicit account selection, and historical/incoming capture only for selected accounts. Imports start unreviewed; unmatched alerts wait outside the ledger for explicit assignment or dismissal. Raw message bodies and authentication messages are not stored.
- Editable categories, tags, notes, merchant names and exact-match future merchant-category rules. Optional OpenAI learning suggests bounded SMS formats and merchant categories for approval; approved rules run locally.
- Date ranges and Indian financial-year selection; search, multiple-category include/exclude filters, amount/type/status/source/tag filters and sorting.
- Bulk categorisation, related-entry links, manual duplicate comparison, deletion tombstones and deleted-entry recovery.
- Local accounts, opening balances, dated balance observations, provisional calculated balances and account-label reassignment for subsequent SMS imports.
- Monthly category budgets; manually entered recurring bills, payment matching and optional generic reminders.
- Private on-device groups, equal/custom shares with exact paise allocation, settlements and separate personal-spending amounts. Groups do not contact other people.
- Encrypted receipt images on saved entries, readable CSV export, mapped CSV import with validation/preview, and monthly PDF reports.
- SQLCipher ledger encryption, Android Keystore key protection, optional device-credential app lock and password-encrypted backups.
- Durable transaction outbox, pinned HTTPS desktop pairing, retry/status handling and idempotent CSV projections for the personal-finance data bank.

No backend account, advertisements, analytics, lending, investment sales or paid data API is required.

## Build and install

Use JDK 17 or 21 and Android SDK 36 with its build tools. Set `ANDROID_HOME`, or create an ignored `local.properties` with your SDK path. The repository uses Gradle 8.14.3.

From this directory:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`. Instrumented checks need an emulator/device. Unit tests and fixtures use synthetic data only.

Start with manual entries, or open Settings → Scan transaction alerts. You may decline SMS access.

### Choose SMS accounts

1. Grant SMS access and choose Past 30 days or Past year. Discovery creates account labels and previews, not transactions.
2. Select the bank accounts/cards you want. Nothing is selected automatically. The preview shows the latest three parsed alerts per account, including amount, type, status and date. Matching counts include duplicates and previously imported alerts.
3. Choose **Import selected accounts**. This both enables incoming SMS capture for those accounts and rescans the chosen range. Exact source matches and deleted transactions are not recreated.
4. Use **Settings → Choose SMS accounts** or **Accounts → Choose SMS accounts** to stop/start future capture. Turning tracking off or archiving an account retains all history; it does not recall previously synced data. Re-enable and scan again to import the missed period.
5. Check **Settings → Unmatched alerts**. Alerts without exactly one identifiable account, or with ambiguous legacy account labels, stay outside totals and sync. Explicitly assign an alert to an existing account or ignore it. This applies only to that alert; it never authorizes future unidentified messages. Ignored alerts stay ignored on an exact rescan.

Supported sender headers are listed in `app/src/main/java/com/ajamuar/ledger/SmsAccounts.kt`. Unknown senders and unsupported formats are skipped; use manual entries or CSV for those banks. Matching combines institution, masked ending and bank/card type, ignoring routing prefixes and the sender's message-type suffix. Masked endings are not globally unique; use the existing duplicate-label merge flow to reconcile legacy/manual labels. Renamed legacy accounts that cannot be identified safely may also need a manual merge. No old transactions are deleted by this update; remove incorrect past entries explicitly from Activity.

Review imports and account mappings before relying on reports. Tracking choices and unmatched-alert decisions are local capture settings, excluded from backups. After restoring a backup, reselect accounts and review/rescan as needed.

## Optional OpenAI learning

1. In **Settings → Scan transaction alerts**, discover and select your accounts. Accounts with recognisable field syntax but unknown transaction wording can appear with “need format setup”; those alerts are not imported until a format is approved.
2. Create your own key at [OpenAI API keys](https://platform.openai.com/api-keys), enable API billing, then open **Settings → Set up AI learning** and paste it into **OpenAI API key → Save key**. ChatGPT subscription billing is separate. Saving a key sends no request.
3. Choose **Prepare last 30 days · preview only**. The app rereads supported bank alerts from Android for selected active accounts. It keeps at most 20 distinct redacted message structures in memory and includes at most 50 merchant names from those examples and unreviewed, uncategorised SMS transactions. It never saves original bodies. Personal-number senders, authentication messages and unselected accounts are excluded.
4. Review the structures and merchant names, then choose **Send preview to OpenAI · uses credits**. Only the displayed redacted structures, bounded parsing choices and redacted merchant names are sent. Account endings, currency values, references, dates, notes and account mappings stay on the phone. Merchant names can still identify a person or business; inspect the preview before sending. The app calls the fixed `https://api.openai.com/v1/responses` endpoint using `gpt-5.6-terra`, with `store: false`, no tools, no automatic retry and a 4,000-output-token cap. OpenAI retention policies still apply; `store: false` is not a zero-retention guarantee. [Responses structured-output documentation](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses).
5. Review the suggested formats and categories; deselect anything incorrect, then choose **Apply selected rules**. A format shows the actual local example amount/merchant and which currency occurrence it would use. Model output can select only locally generated options; it cannot install arbitrary regexes or code, register new bank senders, change account selection, or rewrite existing amounts/types. Format matching is scoped to a recognised bank header and a hash of the redacted structure, so changed structures may need another learning pass.
6. Scan again to import past alerts with approved formats. Merchant rules automatically categorise future SMS and, on approval, currently unreviewed/uncategorised SMS entries in selected active accounts. Reviewed or manually categorised entries are preserved. Earlier incorrect amounts/types need explicit review; rescanning after correcting a format can produce a new source fingerprint rather than repairing an old incorrect entry.

The key is encrypted with the existing Android Keystore vault, never logged or included in the ledger, exports, backups or computer sync. The AI dialogs block screenshots and the key is masked. **Remove key** stops future requests while retaining approved local rules. Delete an approved format from AI settings, or a merchant rule from Settings, to stop its future use; removing a rule does not undo earlier classifications.

Learned format settings are excluded from backups and cleared on restore, like SMS tracking settings. Approved merchant-category rules are included in encrypted ledger backups. No background AI request is scheduled: each paid request requires the send button. Errors, refusals, incomplete responses, invalid IDs and stale merchant-rule changes apply no suggestions. A credit/output limit is not a guaranteed monetary spending cap; API usage is billed by OpenAI.

## Develop in Android Studio

Open **`/Users/ajamuar/Projects/spending-tracker`** using File → Open. Rupee Ledger now lives at the repository root. If Android Studio still has the former `greenfield` project open, close it and reopen this root folder.

1. Let Gradle sync finish. Keep the checked-in wrapper and plugin versions; this build uses Gradle 8.14.3, AGP 8.13.0 and Android SDK 36.
2. In Android Studio → Settings → Build, Execution, Deployment → Build Tools → Gradle, set **Gradle JDK** to JDK 21 (JDK 17 also works). The installed Temurin 21 is `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`. Use the same JDK for terminal builds. See [Android's JDK configuration guide](https://developer.android.com/build/jdks).
3. In Tools → SDK Manager, install **Android SDK Platform 36**, **Build-Tools 36.0.0**, and **Platform-Tools** if missing. Studio should create an ignored `local.properties` pointing to your SDK. On this Mac the SDK is `/Users/ajamuar/Library/Android/sdk`.
4. Select the **app** run configuration and **debug** build variant. If no run configuration appears after sync, create an Android App configuration for the `app` module.
5. For your phone: enable Developer options → USB debugging, connect by USB, and accept the phone's computer authorization prompt. Select it in Studio's device dropdown. For UI work without personal SMS, use Device Manager to start an emulator (the local `Pixel_10` AVD is available). See [Android's device setup guide](https://developer.android.com/studio/run/device).
6. Click **Run ▶**. This builds and launches **Rupee Ledger**, package `com.ajamuar.ledger`. On a phone, exercise discovery, account selection and incoming SMS capture. Emulator checks use synthetic data; they do not prove your bank's templates work.

For each development cycle, edit code in `app/src/main/java/com/ajamuar/ledger`, then click Run again. Use Debug to attach breakpoints. Repeated installs with the same signing key preserve the local ledger; avoid uninstalling or clearing app storage during development. If Studio reports a signature mismatch, keep the existing app and make an encrypted backup before resolving the signing mismatch. Uninstalling erases the encrypted local ledger.

From Studio's Terminal, with its current directory set to the repository root:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ajamuar.ledger.RepositoryTest,com.ajamuar.ledger.SmsScreenTest,com.ajamuar.ledger.UiTest,com.ajamuar.ledger.AiTest,com.ajamuar.ledger.AiScreenTest
```

The second command targets emulator-5554 specifically and runs encrypted-database, fake OpenAI transport and Compose UI tests. Change the serial if your development emulator uses a different one. Use a development emulator for instrumented tests, not your personal ledger. The separate TLS integration test requires disposable receiver pairing as described below. Keep parser fixtures synthetic; never log or commit actual SMS bodies, account numbers or bank exports.

## Pair a computer and deliver data to personal-finance

The receiver requires Python 3.10+ and a local `openssl` executable. It has no Python dependencies. Run it on the computer holding the data bank, on a private network reachable from your phone. The default loopback address is suitable only for local/emulator testing.

Example, replacing the example private IP with your computer's address:

```sh
python3 desktop/receiver.py init --directory .receiver --url https://192.168.1.10:8765
python3 desktop/receiver.py serve --directory .receiver --host 0.0.0.0 --port 8765 \
  --output /Users/ajamuar/Projects/personal-finance/data/mobile
```

Initialization creates a self-signed certificate, private key and pairing configuration in the private, ignored `.receiver` directory. It refuses to overwrite an existing setup. Do not commit or log those files. To revoke a pairing, stop the receiver and create a new setup in a different empty directory, then pair again. Certificates last one year; renew by creating a new setup and re-pairing. This receiver is intended for your private network, not an internet-facing service.

In the app:

1. Set each account's **Repo account ID** to its identifier in your data bank. Blank mappings are rejected by the receiver.
2. Open Settings → Pair computer and paste the contents of the private `pairing.json` file. Pairing is manual in this release; QR scanning is not implemented.
3. Choose Sync now. Later edits queue automatically. Delivery waits while the computer is unavailable; Android's periodic fallback is approximately every six hours, subject to battery/network restrictions. Pause automatic delivery in Settings when needed; Sync now remains available for an explicit one-off delivery.

The receiver owns only these files under the chosen output directory:

| File | Meaning |
|---|---|
| `mobile-transactions.csv` | All non-deleted money movements, including transfers and unposted/excluded entries; classification and status retained |
| `mobile-spending.csv` | Posted, non-excluded expenses/refunds; `amount_inr` represents the personal share; incoming refunds reduce net spending |

Both include stable source IDs and revisions. Two equal-amount payments remain separate. Corrections can clear notes/categories, and deletions remove rows from projections. The SQLite inbox remains the durable source for rematerialization. Each CSV replacement is atomic, but the pair of files is not a single atomic snapshot.

The existing `data/transactions.csv`, `accounts.csv`, `balances.csv` and hand-entered records are never overwritten. Financial analysis must explicitly read the appropriate mobile projection; do not append every money movement to consumption totals. The receiver does not update repo account definitions, transfer balances, commit, push, or merge Git branches. The phone holds no GitHub credential.

The CSV projections are machine data, not spreadsheet presentation exports. The app's separate CSV export escapes spreadsheet formula prefixes and records that encoding for its importer.

### Backup and recovery

Backups contain ledger identity, transaction history/tombstones, accounts/mappings/balance observations, budgets, bills, private groups/splits, category rules and account aliases. They use AES-256-GCM with a PBKDF2-HMAC-SHA256-derived password key. Use at least 12 characters and keep the password separately.

Restore is allowed only into an empty ledger with no existing plans/rules; it does not erase an existing ledger. Pairing secrets and receipt images are excluded. If the computer has revisions newer than an old backup, sync stops visibly rather than pretending the older phone is current. Reconcile that older backup before continuing. Only one phone should edit a ledger identity.

### CSV migration

The importer supports an explicit amount/date/description/category/type mapping and previews all rows before an atomic import. Invalid dates/amounts reject the whole import. The selected file imports into one chosen account; split multi-account axio exports by account first. Supported dates are ISO, day/month/year, day-month-year and day-abbreviated-month-year.

Rows with stable IDs use those IDs; otherwise exact row fingerprints and occurrence counts make identical-file replay safe. Overlapping or edited exports without stable IDs can duplicate history. This is not fuzzy reconciliation. The actual current axio CSV has not been supplied or tested, so no automatic axio-format compatibility is claimed.

## Verification

- Kotlin tests: exact money, grouping/overflow rejection, parser authentication rejection, card/transfer accounting, balance direction, CSV quoting/date validation, import replay, split rounding and authenticated backup integrity.
- Android tests: encrypted Room operations, queue coalescing/acknowledgment, SMS replay/reversal/edit/delete behavior, backup identity recovery, private splits and account alias restoration.
- Compose UI flow: create an expense, find it in Activity, create a bill and create a private group.
- Python tests: replay, edits/clearing, stale revisions, tombstones, batch atomicity, revision collisions, personal versus full amounts and preservation of existing files.
- Optional real TLS integration test: a disposable receiver handles an emulator transaction, correction and deletion. It runs only when an ignored test asset `app/build/generated/integrationAssets/pairing.json` exists and the development ledger contains no live entries. No receiver secret is packaged in the application APK.

The first development session exercised pinned TLS over an emulator-to-host reverse connection, not a real phone over Wi-Fi. Real OEM background behavior, your banks' actual templates and full visual axio parity remain unverified.

Verified on 22 October 2026: debug APK build, 11 Kotlin tests, seven emulator tests (including UI and pinned TLS with rejection of a wrong certificate), and five Python tests. No failures or skipped tests in that run. Android lint reports no errors; remaining warnings are dependency-version/style recommendations and the intentional certificate-pinning trust manager.
