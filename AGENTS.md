# Project collaboration

This is the standalone Pure Ledger Android repository. Work at this root; the sibling yifei-ledger workspace is a private development/archive workspace and must not be published or cleaned as part of this repository.

## Product and implementation

- Read README.md, docs/STATUS.md, docs/ROADMAP.md and relevant architecture notes before changes. Kotlin is the app language; core must stay independent of Android APIs.
- Accessibility bookkeeping, exact money, existing Excel migration and data export are core. Do not substitute manual entry for verifying a payment parser.
- Keep applicationId io.github.localledger.app and the full accessibility component stable. App name Pure Ledger / 纯净记账 is a display identity. Preserve data and permissions; do not uninstall apps or clear user data.
- User confirmation, source receipts, account balances and learned selections must share explicit transaction boundaries. Add meaningful regression checks for money, import, storage and runtime changes.
- Only read payment apps in an explicitly requested scenario. User pages/logs/exports belong in ignored .private/. Public fixtures must be synthetic or approved and anonymized.
- Do not add legacy APKs, decompiled code/assets, signing keys, personal files or local machine paths. Preserve GPL-3.0-only and all third-party notices/provenance.
- UI text describes useful data, status or actions. Avoid developer replies, feature promises, redundant purity/offline/advertising labels, and decorative controls. Keep full amounts, adequate contrast and 48dp touch targets.
- Notifications are optional. Reopening may restore an enabled status notification; do not bypass permission/channel blocks or immediately fight dismissal.

## Validation and delivery

- scripts/Project.ps1 Test runs core checks; TestAndroid runs local Android checks; Build produces the APK. Standard Gradle commands are in docs/DEVELOPMENT.md.
- Run native checks only against the explicitly named disposable LocalLedger_API35 AVD using scripts/run_native_checks.py. Never use connectedAndroidTest against arbitrary connected devices.
- Device commands use an explicit serial from ignored local configuration. The AI owns implementation, tooling and diagnosis; ask the user only for device operations and real behavior feedback.
- Keep results in ignored artifacts/ or .private/. Update docs/STATUS.md with concise current evidence and pending device checks, rather than appending conversation transcripts or phase-by-phase prompts.
- App version is in gradle.properties. Follow docs/RELEASING.md: public version and Android upgrade code are separate from database/exchange versions. No destructive version resets.
- Never publish, push, message others or upload personal data unless explicitly authorized. A local tag or release-ready build is not a public release.
