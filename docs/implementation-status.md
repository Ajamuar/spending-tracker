# Greenfield implementation status

22 October 2026 · project: repository root · working name: Rupee Ledger.

This is a working first release, not a claim that the 62-surface design is fully complete or that axio has been visually reproduced. Rupee Ledger is the only maintained app; its source now lives in root `app/`. Screens are often combined into sheets rather than separate routes.

## Implemented

| Workflow | Plan coverage |
|---|---|
| Optional permissions, 30-day/year SMS scan, progress/cancel, account review | S02–S05, S51; scan controls are combined in Settings |
| CSV mapping/preview/atomic import, encrypted backup/empty-ledger restore, device-credential unlock | S06–S08, S54–S55 |
| Overview, month/custom/FY selection, search/timeline and advanced filters | S09–S13 |
| Add/edit/details, categories/tags/notes, source metadata, links, bulk category updates, duplicate comparison, deletion and recovery | S14–S15, S20–S24; labels are editable inline |
| Exact merchant category rules and encrypted receipt attachments | Parts of S18, S19, S53 |
| Accounts, opening/observed/calculated balances, account activity and duplicate-label reassignment | S25–S29, combined into account cards/settings |
| Category breakdown/drill-down, fixed monthly category budgets, CSV and period PDF export | Core of S30–S33, S35 |
| Manual bills, recurring next-date advancement, manual/matched payment states, generic reminders | S36–S39 |
| Private groups, paid-by-member expenses, equal/custom shares, balances and settlement recording | Core of S40–S45 |
| Settings, private pairing, account mapping, queue status, retries and visible sync failures | S50, S57–S60; manual configuration rather than QR |

## Remaining from the plan

- S01 dedicated onboarding; S16/S17 standalone category/tag lifecycle managers (creation/editing already work inline); complete merchant history/alias management in S18.
- Receipt zoom/camera capture and receipt inclusion in portable encrypted backups. Current attachments use the system photo picker and a fixed-size viewer.
- Full comparison charts, monthly review S34, flexible budget start days, historical per-month budget settings and budget carry-forward controls.
- S46 group/member lifecycle editing/deletion beyond record removal; shared groups/chat/invitations S47–S49 require a service and remain outside the local first release.
- Automatic bill/statement ingestion and authoritative card dues. Current bills and balance observations are manual; SMS-derived transactions are always provisional until reviewed.
- S52 dedicated unrecognized-message inspector; broader institution-specific parsing and deep links from transaction notifications.
- S56 complete appearance/preferences and S61 dedicated diagnostics/help screens; S62 optional Gmail ingestion.
- QR pairing, a background desktop service installer, full per-revision sync history and a user-facing stale-backup conflict resolver. The receiver currently runs as a foreground Python process; failed/stale changes are retained and reported.
- Automatic ingestion of the mobile CSV projections by the existing personal-finance analysis code, account/balance-table synchronization and optional Git automation. The receiver can write into that repo but does not silently modify its existing consumers or files.
- Actual axio CSV validation and an authenticated current-version visual walkthrough.

## Validation boundaries

The development APK and tests exercise a real Android emulator. Synthetic records only were used. SMS permission/capture, encrypted storage and paired TLS have implementation coverage, but real bank messages and OEM hardware behavior have not been validated. Neither a Play Store release nor production pairing/remote Git writes were performed.

The build, 11 Kotlin tests, seven instrumented tests and five receiver tests passed. The instrumented suite includes a UI flow and rejection of an incorrect TLS certificate fingerprint while retaining pending changes. Lint has no errors; it retains non-blocking dependency/style warnings and the deliberate self-signed-certificate pinning warning.

Receiver credentials stay outside source control. Financial records, SMS/email bodies and statements are absent from fixtures. The unrelated existing `.DS_Store` file was left alone.


## SMS account selection update — 22 October 2026

- Historical scans discover accounts and preview up to three parsed alerts per account before any import. All new accounts default off; confirmation explicitly enables selected accounts.
- Both historical and incoming ingestion check tracking and archive state before saving transactions or outbox changes. Turning tracking off retains existing history.
- Sender routing prefixes/suffixes are normalized; account identity uses institution, masked ending and bank/card type. Existing legacy labels/source fingerprints are reused where unambiguous; manual account merge resolves ambiguous or renamed labels.
- Supported institution headers are explicitly limited in `SmsAccounts.kt`. Unknown headers, authentication and promotional/due messages are skipped. Alerts without one safe account match enter an encrypted, structured review queue outside totals/sync; bodies are never retained. Review supports explicit per-alert account assignment or durable ignore.
- Capture settings and unresolved/ignored alerts are not in backups. Restore clears capture settings and requires reselection. Existing incorrectly imported history is not automatically removed.
- Android Studio development instructions are in the root `README.md`; open the `spending-tracker` repository root.
- Verification for this update: debug APK build, 12 JVM tests and 11 emulator tests passed with no failures or skips. Android lint has zero errors and 24 warnings. The unchanged Python receiver and optional TLS integration test were not rerun. Real-phone SMS templates remain unverified.

## Repository layout consolidation — 22 October 2026

Rupee Ledger now occupies the repository root (`app/`, `desktop/` and the Gradle wrapper). The former My Expenses source, schemas, version catalog and downloadable APK have been removed. The application ID, schema history and signing configuration of Rupee Ledger are unchanged. Open the repository root in Android Studio; builds and test commands run there.

Verification from the consolidated root: debug build, 12 JVM tests, 11 emulator tests and five Python receiver tests passed with no failures/skips. Android lint reports zero errors and 24 warnings. The optional paired TLS test was not rerun.
