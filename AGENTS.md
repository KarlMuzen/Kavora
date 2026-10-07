# AGENTS.md: rules for every task in this repo

Project: Kavora (Android, Kotlin, Jetpack Compose). Read `README.md` and `docs/design.md` before any task.
Where this file or a task prompt conflicts with `docs/design.md`, **this file and the prompt win** (see "Scope overrides").

## Hard rules

1. **Release builds must not declare `INTERNET`.** No analytics, ads, Firebase, remote crash-reporting SDKs, VPN service, accessibility service, or background service/worker/receiver doing periodic work.
2. **Dependencies are limited to:** Kotlin stdlib, Compose (Material 3, activity-compose, lifecycle-viewmodel-compose, navigation-compose), kotlinx-coroutines, Shizuku (`dev.rikka.shizuku:api`, `:provider`), plus `androidx.core` for FileProvider in DIAG builds. JSON uses the platform `org.json`. Ask before adding anything else.
3. **Shell safety:** commands are typed `Command` classes that produce argv lists, run with `ProcessBuilder` inside the Shizuku UserService. Never `sh -c`. No free-form shell input anywhere in the UI.
4. **Every write command has an `inverse` or is flagged `destructive`.** `Guard` runs before every write. After a write, re-read state to verify; exit code 0 alone is not success.
5. **Never force-stop apps or clear caches automatically.** Nothing runs unless the user taps it.
6. **Never invent command output or syntax.** Anything that depends on ROM/device behavior is marked `[verify]` in a code comment, gated behind capability probes where it can fail, and surfaced in the Diagnostics screen.
7. **Identity and SDK:** package `io.github.karlmuzen.phonemanager`, minSdk 30 (public release planned after friend testing), targetSdk 34, compileSdk = latest stable, Java 17.
8. **Versions:** use the latest stable AGP, Kotlin (with the Compose compiler plugin), Compose BOM, Gradle, and Shizuku API 13.x. Check Maven metadata at build time. If dependency downloads fail, stop and report; do not guess versions.
9. License GPL-3.0. Keep file headers short.

## Working agreement (anti-hallucination)

- **One subphase per run.** Do only the subphase in the prompt, report, and stop. Never start the next one and never stub future features.
- **Source of truth order:** AGENTS.md > the current prompt > docs/design.md > your own assumptions. Assumptions must be listed in the report.
- **Unknown behavior = stop and ask.** Do not guess command syntax, command output, ROM behavior, or library APIs. If a library API is uncertain, read its source or docs in the dependency; if still unsure, report it.
- **Fixtures:** test fixtures are labeled `synthetic` unless the user pasted real device output into `docs/fixtures/`. Never describe a synthetic fixture as device-verified.
- **Evidence log:** `docs/command-notes.md` is updated only from Diagnostics reports the user pastes. Never fill it from memory.
- **Phase gates:** a phase marked GATE is complete only when the user confirms it; do not assume.

## Scope overrides to docs/design.md (lean v0.1)

**Keep:** typed `Command` + `Guard` + `Executor` with verify-after-write; UserService shell (`execBatch` only); capability probes (`Caps`); Apps (Disable, Enable, UninstallForUser, InstallExisting); Firewall (chain 3) with boot-id stale detection; DNS with `pendingRevert`; Home vitals via SDK APIs; Tweaks; Diagnostics (below).

**Cut or defer:** persisted change log; persisted undo (use an in-memory, session-only undo stack built from `inverse`); tags, profiles, presets, intents, imports; `debloat.json`; Hide, Suspend, ClearData; `execStream`; biometric lock; boot notification.

**Persisted state is limited to** device-protected SharedPreferences JSON holding only: firewall (`chain3` flag, blocked map `pkg -> boot id`), `dns.pendingRevert`, and the capability cache.

## Build types

| Type | DIAG | Notes |
|---|---|---|
| debug | true | `applicationIdSuffix ".debug"` |
| beta | true | `.beta` suffix, R8 on, for friend testing |
| release | false | R8 on; no diagnostics code reachable, no FileProvider |

## Diagnostics policy (dev/beta only)

Crash and diagnostic data is **local only**: written to app storage and shared by the user through the system share sheet. No network, no SDKs. Remote crash reporting would require `INTERNET` and is out of scope.

## Definition of done (every task)

- `./gradlew testDebugUnitTest assembleDebug` passes (generate the Gradle wrapper if missing).
- CI is green, including the release check that the APK does not request `INTERNET`.
- Add JVM unit tests for pure logic (argv golden tests, Guard, state derivation, boot-id logic, executor against a fake shell).
- Finish with a report: files changed, tests run, `[verify]` items, open questions.
- Do not add features that the task prompt does not list.
