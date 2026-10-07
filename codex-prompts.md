# Codex prompt pack v2: phases and subphases

**Rules of use**
- Paste the **header** plus **one subphase** per Codex run. Merge only when CI is green, then move on.
- Never paste two subphases together. Never skip a GATE.
- Paste each report's `[verify]` list and open questions back to Claude before the next phase.

**Priority logic:** foundation first, then safety, then debugging tools, then your highest-value feature (DNS, already proven on your phone), then on-device verification, and only then the features that depend on unverified ROM behavior (Apps, Firewall, Tweaks).

| Phase | Goal | Gate before next phase |
|---|---|---|
| 1 Foundation | project, CI, INTERNET check, docs | CI green; APK opens on your phone |
| 2 Shizuku connection | shell service and status | Home shows Shizuku Ready on your phone |
| 3 Safety engine | commands, Guard, Executor | unit tests green |
| 4 Diagnostics base | crash log, command log, share | you can share a log from the phone |
| 5 DNS | first real feature | DNS toggles on your phone and reverts safely |
| 6 Verification | capability probes, behavior tests | **GATE: you send Claude the report** |
| 7 Apps | freeze, uninstall, restore | works on your throwaway app |
| 8 Firewall | block, stale detection | survives your reboot test |
| 9 Home and Tweaks | vitals, tweaks | values match aShell You |
| 10 Hardening | R8, beta release | friends can install |

**Header: paste before every subphase**
```
Read AGENTS.md first. Do ONLY this subphase. Do not start later subphases and do not stub future features. If anything is unclear or a command's behavior is unknown, STOP and ask instead of guessing. Run ./gradlew testDebugUnitTest assembleDebug. Report: files changed, tests run, assumptions, [verify] items, open questions. Then stop.
```

---

# Phase 1: Foundation

## 1.1 Project skeleton
```
Subphase 1.1: project skeleton (no CI yet).
Create a single-module Gradle project (:app, Kotlin DSL, version catalog) using the versions rule in AGENTS.md. Package io.github.karlmuzen.phonemanager, minSdk 30, targetSdk 34. Compose Material 3 theme (dynamic color on Android 12+, light/dark), one Activity, bottom nav with placeholder screens: Home, Apps, Firewall, DNS, Tweaks. Manifest permissions: none yet.
Do not add Shizuku, build types, or any logic.
Acceptance: assembleDebug passes; app launches and navigates between placeholders.
```

## 1.2 Build types, CI, INTERNET gate
```
Subphase 1.2: build types and CI.
Add build types per AGENTS.md: debug (DIAG=true, suffix .debug), beta (DIAG=true, suffix .beta, R8), release (DIAG=false, R8); expose BuildConfig.DIAG. Beta signing uses CI secrets (ANDROID_KEYSTORE_B64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) when present, else the debug key.
Add .github/workflows/ci.yml: JDK 17, Gradle setup, unit tests, assembleDebug and assembleBeta, upload APKs. Add a release job: assembleRelease, then FAIL if `aapt2 dump permissions` on the release APK lists INTERNET. Print APK sizes.
Acceptance: CI green on a push; deliberately adding INTERNET in a scratch branch makes the release job fail (describe how you checked).
```

## 1.3 Docs
```
Subphase 1.3: docs only.
Move design.md to docs/design.md if needed. Update README.md: apply design.md section 11 changes, the lean scope and cuts from AGENTS.md, minSdk 30 (public release planned after friend testing), and a "Diagnostics (dev/beta builds only)" section stating that all diagnostics are local and shared via the system share sheet. Create empty docs/command-notes.md with the table header | command | sdk | works/differs/n/a | stdout sample | survives reboot |.
No code changes.
```

---

# Phase 2: Shizuku connection

## 2.1 Service, allow-list, protocol
```
Subphase 2.1: shell service (no UI).
- AIDL IShellService: destroy() = 16777114 and execBatch(String requestJson) = 1 only. Enable buildFeatures.aidl.
- core/Allow.kt shared by app and service. Binaries: pm, cmd, settings, dumpsys, am, sm, wm, ping, getprop, id, device_config. cmd services: package, appops, connectivity, netpolicy, deviceidle.
- ShellService: ProcessBuilder(argv), never sh -c; reject disallowed argv; per-command timeout; 200 KB cap per stream; reply under ~600 KB; set `truncated`; destroy() calls exitProcess(0).
- Shell interface, FakeShell, ExecResult(id, exit, out, err, truncated, ms), JSON request/response (org.json).
Tests: Allow rules, JSON round-trip, truncation.
Do not add the Shizuku client, permissions, or UI.
```

## 2.2 Client, state, Home card
```
Subphase 2.2: Shizuku client and status.
Add the Shizuku API + provider dependencies and the manifest entries exactly as in the Shizuku-API README (ShizukuProvider; QUERY_ALL_PACKAGES). Still no INTERNET.
ShellClient: Shizuku.bindUserService (daemon(false), version = BuildConfig.VERSION_CODE). StateFlow<ShizukuState> with exactly NotInstalled, Stopped, NoPermission, Binding, Ready(shell, uid), Died. Use addBinderReceivedListenerSticky, addBinderDeadListener, addRequestPermissionResultListener.
Home screen: a status card with a short start checklist and a "Grant permission" button. Add one read-only button "Run id" that executes `id` through the shell and shows the output.
Acceptance on device (note as [verify]): card reaches Ready; "Run id" shows uid=2000(shell).
```

---

# Phase 3: Safety engine

## 3.1 Command model
```
Subphase 3.1: command model (design.md section 3, trimmed).
Pkg (validated value class), Persist enum, sealed Command {argv, persist, touches, inverse, destructive, describe(), ok()}, Plan(label, steps, stopOnError).
Implement ONLY these commands, argv exactly as in design.md section 3: Disable, Enable, UninstallForUser, InstallExisting, SetChain3, FwBlock, FwAllow, DnsSet, plus reads ListPackages, Get, Help, Ping.
Tests: golden argv per command; Pkg validation; inverse correctness.
Do not build Guard or Executor.
```

## 3.2 Guard
```
Subphase 3.2: Guard.
Verdicts BLOCK > CAUTION > OK. BLOCK = static glob list from README plus a runtime set: default home, default IME, default dialer, default SMS, current WebView, active device admins, this app, Shizuku, FLAG_PERSISTENT apps, sharedUserId starting with android.uid. CAUTION = com.oplus.*, com.coloros.*, com.heytap.*, com.realme.*, and any destructive command. Provider of the runtime set is an interface with a fake for tests.
Tests: each rule, precedence, glob matching.
Do not wire to UI or Executor yet.
```

## 3.3 Executor, undo, preview
```
Subphase 3.3: Executor and ActionHost.
Executor: mutex; before-probes only where an inverse needs a snapshot; one execBatch per plan; verify by re-reading; mark each step applied/failed; session-only undo stack in memory built from inverse. Accept an optional CommandObserver (no-op default) that receives every command and result.
ActionHost (Activity scope): PreviewSheet (label, first 5 describe() lines then "+N more", persistence tag, caution badge, Cancel/Run) and an Undo snackbar. BLOCK verdicts cannot run.
Tests vs FakeShell: partial failure, stopOnError, verify-failure => step failed, undo plan, BLOCK refusal.
```

---

# Phase 4: Diagnostics base (DIAG builds only)

## 4.1 Crash and command logs
```
Subphase 4.1: local logs. Compile out of release.
CrashLogger: Thread.setDefaultUncaughtExceptionHandler writes filesDir/diag/crash-<ts>.txt with stack trace, versionName/Code, build type, Build.FINGERPRINT, SDK, ShizukuState, and the last 100 commands (argv, exit, ms). Never include the installed-package list. Chain to the previous handler.
CommandLog: in-memory ring buffer (200) implementing CommandObserver, attached to the Executor.
Tests: ring buffer, crash report formatting.
```

## 4.2 Diagnostics screen and share
```
Subphase 4.2: Diagnostics screen.
Nav item only when BuildConfig.DIAG. Shows environment info, recent commands, list of crash files, a "Clear logs" button, and a "Share" action. Share: FileProvider declared only in src/debug and src/beta manifests, ACTION_SEND via the system share sheet, with a preview of the content first. Never any network.
Acceptance: release APK contains no Diagnostics entry and no FileProvider (check with aapt2 and report how).
```

---

# Phase 5: DNS (first feature)

## 5.1 Read-only DNS screen
```
Subphase 5.1: DNS screen, read-only.
Read private_dns_mode and private_dns_specifier through the shell and show them. Show the presets list (mullvad-base base.dns.mullvad.net as default, mullvad-adblock adblock.dns.mullvad.net, adguard dns.adguard-dns.com, custom hostname, Off) without applying anything. Show these limits in plain text: domain-level only; provider sees lookups; an active VPN may override Private DNS.
Tests: parsing of settings output.
```

## 5.2 Apply and auto-revert
```
Subphase 5.2: apply DNS safely (design.md section 7.3).
Plan with stopOnError=true: snapshot mode+specifier -> persist dns.pendingRevert -> set specifier -> set mode hostname -> wait 2 s -> ping -c 1 -W 3 <probe domain> -> on resolve clear pendingRevert; on failure restore the snapshot and report. Probe domain comes from a neutral list (example.com, wikipedia.org, mozilla.org), never a domain the chosen list is likely to block. Pass = the name resolved even with 100% packet loss. Custom hostname validated with the host regex in design.md. Off = private_dns_mode off, specifier untouched.
Home banner "DNS change unconfirmed [Revert] [Keep]" when pendingRevert != null at launch.
Tests with FakeShell: success, resolve failure, app killed mid-plan.
```

---

# Phase 6: Verification

## 6.1 Capability probes
```
Subphase 6.1: Caps (design.md section 6, only what v0.1 needs).
Probes: `cmd package help` lists install-existing; `cmd connectivity help` lists set-chain3-enabled and set-package-networking-enabled (and get-package-networking-enabled if present); `settings get global boot_count` returns an integer; `getprop ro.build.version.sdk`.
Cache in prefs keyed by Build.FINGERPRINT + uid; re-run when the key changes. A failed probe hides the feature with "not available on this ROM". A passed probe means the command exists, not that it behaves; mark behavior [verify].
Show probe results in the Diagnostics screen.
Tests: parsing against fixtures labeled synthetic.
```

## 6.2 Behavior tests and reboot matrix
```
Subphase 6.2: behavior tests (DIAG only).
In Diagnostics: the user picks ONE throwaway user app. Run the design.md section 9 sequence as typed commands (disable/enable, uninstall -k then install-existing, chain-3 block/get/allow, ping semantics, boot_count). Each write is immediately followed by its inverse; Guard is enforced; stop on the first unexpected state.
Reboot matrix helper: store a pending checklist of applied items, re-read them after the next boot, record which survived.
Report format: docs/command-notes.md rows (| command | sdk | works/differs/n/a | stdout sample | survives reboot |) with a preview, shared via the Phase 4 share flow.
```

**GATE 6:** run the behavior tests and the reboot matrix on your phone, then paste the report to Claude. Claude updates `docs/command-notes.md` and confirms which Phase 7-9 features are real on your ROM.

---

# Phase 7: Apps

## 7.1 Read-only list
```
Subphase 7.1: Apps list, read-only (design.md section 7.1, states ENABLED / DISABLED / REMOVED).
Load `pm list packages --user U`, `-d`, and `-u` in parallel on Dispatchers.IO. REMOVED = in -u but not in the default list; DISABLED = in -d; else ENABLED. Filters: User, System, Disabled, Removed. Search. Labels cached; icons lazy-loaded per visible row into a byte-sized LruCache (about 8 MB). Guard badges shown. No actions yet.
Tests: state derivation from synthetic package-list fixtures.
```

## 7.2 Freeze and unfreeze
```
Subphase 7.2: Freeze / Unfreeze.
Multi-select with actions Freeze (Disable) and Unfreeze (Enable) through Plan -> Guard -> PreviewSheet -> Executor. BLOCK rows cannot be selected; CAUTION rows show a badge. No force-stop anywhere.
Tests: bulk plan skips BLOCK packages; inverse plan.
```

## 7.3 Uninstall for user and restore
```
Subphase 7.3: Uninstall for user / Restore.
Add UninstallForUser (destructive, confirmation required) and Restore (InstallExisting) for REMOVED rows. Verify by re-reading the lists.
Tests: plan and verification against FakeShell.
```

---

# Phase 8: Firewall (needs Gate 6 results)

## 8.1 Gating and read-only
```
Subphase 8.1: Firewall screen, read-only. Gate the whole module on Caps fw.chain3 and use only commands that Gate 6 confirmed (see docs/command-notes.md). List apps with their desired state; show actual state only if the get command was confirmed. Warn that rules reset on reboot.
```

## 8.2 Block and allow
```
Subphase 8.2: block / allow.
Enable: SetChain3(true) in its own batch (stopOnError=true), then block batch (stopOnError=false). Persist only chain3 and blocked{pkg: bootId}. Through Plan -> Guard -> Preview -> Executor. Guard BLOCK rows are not blockable.
Tests: plan ordering, persistence.
```

## 8.3 Stale detection and restore
```
Subphase 8.3: reboot handling.
Boot id: Settings.Global.BOOT_COUNT; fallback currentTimeMillis() - elapsedRealtime() with +-2 min tolerance. Row states ALLOWED, BLOCKED, BLOCKED_STALE (amber). Home banner "Firewall rules reset after reboot [Restore]" builds one Plan: SetChain3(true) + all stale blocks.
Tests: boot-id logic, stale detection, restore plan.
```

---

# Phase 9: Home and Tweaks

## 9.1 Vitals
```
Subphase 9.1: Home vitals via SDK APIs (BatteryManager, ActivityManager.MemoryInfo, StatFs) so Home works with Shizuku stopped. Skin/CPU temperature via `dumpsys thermalservice`, parsed defensively; show "n/a" if the format is unexpected [verify]. Banner order: Shizuku card, reboot restore, DNS unconfirmed.
Tests: thermal parser with synthetic fixtures.
```

## 9.2 Tweaks
```
Subphase 9.2: Tweaks (every change through Plan/Guard/Preview).
Animation scales (window/transition/animator: 0 / 0.5 / 1); phantom-process fix (settings_enable_monitor_phantom_procs false; device_config set_sync_disabled_for_tests persistent; device_config put activity_manager max_phantom_processes 2147483647) [verify persistence]; Doze whitelist for Shizuku (dumpsys deviceidle whitelist +moe.shizuku.privileged.api). Show current values read from the device.
Tests: argv golden tests.
```

---

# Phase 10: Hardening

## 10.1 Release prep
```
Subphase 10.1: hardening.
R8 keep rules for Shizuku and the AIDL service; verify beta (minified) works. Lint clean; remove unused resources and dependencies. CI: publish beta APKs as a GitHub pre-release on tags v*-beta; keep the INTERNET check as a required gate. README: install steps (start Shizuku, grant permission), known limits, how testers send a diagnostics report, GPL-3.0 notice. Report APK size and the dependency list.
```
