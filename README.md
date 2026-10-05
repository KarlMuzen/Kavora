# Shizuku Phone Manager

Lightweight, privacy-first replacement for Realme/Oplus Phone Manager, built on [Shizuku](https://shizuku.rikka.app/). One app for app control, firewall, ad-blocking DNS, cleanup and tweaks.

> Status: **blueprint only** (no code yet).

## 1. Principles

- **No `INTERNET` permission.** The app cannot phone home. Verifiable in the manifest.
- **No analytics, ads, Firebase or Google dependency.**
- **No background service, no accessibility service, no VPN.** Runs only while open (idle cost = 0 CPU, 0 heat).
- **Minimal dependencies:** Kotlin, Jetpack Compose (Material 3), Shizuku API. Nothing else. R8 shrink on release.
- **Fixed command allow-list.** No free-form shell input; every action is a typed command.
- **Everything reversible.** Destructive actions need confirmation and are logged locally so they can be undone.
- **Local storage only** (SharedPreferences). No cloud, no accounts.
- Own app lock via BiometricPrompt (no always-on watcher).

## 2. Feature map (Realme Phone Manager -> this app)

| Realme feature | Decision | Implementation (via Shizuku shell; verify each on device) |
|---|---|---|
| Storage cleanup / App data clean-up | Build | `pm trim-caches`, `pm clear <pkg>` (per-app, confirmed) |
| App management | Build | `pm disable-user --user 0`, `pm enable`, `pm uninstall -k --user 0` |
| Hide apps | Build | `cmd package hide` if permitted, else disable-user |
| Recover system apps | Build | `cmd package install-existing --user 0 <pkg>` + local change log |
| App permission | Build | `pm grant/revoke`, `cmd appops set` (replaces App Ops) |
| Multiple users | Build | `pm list users`, `pm remove-user <id>` (warn: deletes clone data) |
| Data usage | Build | Per-app stats (`dumpsys netstats`) + firewall |
| Battery management | Build | temps (`dumpsys battery`, `thermalservice`), `dumpsys deviceidle whitelist`, `am set-standby-bucket`, `appops RUN_ANY_IN_BACKGROUND` |
| System boost | Reinterpret | Real actions only: dexopt, TRIM, animation scale, background restriction. No fake "boost" |
| Trinity engine | Partial | CPU/RAM Vitalization are kernel/OS-level, not replicable. ROM side approximated: `cmd package compile -m speed-profile -a`, `sm fstrim` / idle-maint, duplicate finder |
| Viruses and risk / App security control / Payment protection | Reinterpret | Local risk audit, no signature scanner (needs network): accessibility services, overlay apps, device admins, unknown installers, risky permission combos. Play Protect stays |
| Photo cleanup / Video cleanup | Reinterpret | Largest files + exact duplicates (size + hash). No AI similarity |
| App lock | Drop | Needs an always-on watcher. Keep Realme's |
| Private safe | Drop | Use Cryptomator |
| Theft protection | Drop | Needs network + account. Google Find Hub already covers it |
| Emergency SOS | Drop | Keep OS feature |

Added (not in Phone Manager): **ad-blocking DNS**, **firewall**, **tweaks**, biometric lock for this app.

## 3. Modules

### Dashboard
Battery level/temp, skin/CPU temp, RAM and swap, storage. Read-only (`dumpsys battery`, `dumpsys thermalservice`, `/proc/meminfo`, `df`).

### Ad-blocking DNS
One-tap Private DNS via `settings put global private_dns_mode hostname` + `private_dns_specifier <host>`.
- Presets: `base.dns.mullvad.net` (default; ads, trackers, malware), `adblock.dns.mullvad.net`, `dns.adguard-dns.com`, custom hostname (e.g. a NextDNS ID), and Off/Cloudflare (`one.one.one.one`).
- After switching, check connectivity and **auto-revert on failure**.
- Limits (shown in-app): domain-level only, cannot remove in-app ads served from content domains; the provider sees DNS lookups; an active VPN may override Private DNS.
- Persists across reboots.

### Firewall (no VPN)
Per-app network block using Android's chain-3 mechanism, the same approach as ShizuWall:
`cmd connectivity set-chain3-enabled true|false`, `cmd connectivity set-package-networking-enabled true|false <pkg>`.
- Rules are cleared on reboot (Android behavior). Re-apply = one tap; automatic re-apply on Shizuku start is an open question (see section 8).
- Works alongside a VPN (does not use the VPN slot).

### Apps
Searchable list with filters (user / system / disabled / removed). Actions: freeze, unfreeze, uninstall for user, restore, clear cache/data. **No force-stop by default** (it fights Trinity's long background retention).

### Permissions
Per-app permission and appop viewer/editor.

### Cleaner and optimisation
Cache trim, per-app data clear, large files, duplicates, dexopt (`cmd package compile`), TRIM. Never scheduled; always manual.

### Tweaks
Animation scales, phantom-process fix (`settings_enable_monitor_phantom_procs`, `max_phantom_processes`), Doze whitelist for Shizuku, "re-apply all" button.

### Users
List profiles (e.g. the Realme clone-apps profile, user 999) and remove them with a data-loss warning.

### Risk audit
Local heuristics only, listed with the reason for each flag. No verdicts, no cloud lookups.

## 4. Safety rules

- Protected packages are never freeze/uninstall targets: `com.android.systemui`, `com.android.launcher`, `com.android.phone`, `com.android.settings`, `com.google.android.gms`, `com.android.vending`, `moe.shizuku.privileged.api`, `com.oplus.battery`, `com.oplus.athena`, `com.oplus.nas`, `com.oplus.exsystemservice`, `com.mediatek.*`, `com.android.providers.*`.
- Oplus/Realme packages show a caution tag; unknown packages are never removed in bulk.
- Every change is written to a local change log so it can be undone from the Recover screen.
- Reboot reverts firewall rules; a reboot is the universal escape hatch.

## 5. Architecture

- Single Gradle module, package `io.github.karlmuzen.phonemanager`, minSdk 30, targetSdk 34.
- Kotlin + Compose Material 3, single Activity, ViewModel per screen, no DI framework, no network or image libraries.
- `ShizukuShell`: wraps Shizuku permission and process execution. Only a sealed `Command` type can be run, so there is no arbitrary shell input.
- Each module is a small class that builds `Command`s and parses their output.
- Settings and logs in SharedPreferences; no database.

## 6. Roadmap

- **v0.1:** Shizuku connect/permission, dashboard, Apps (freeze/uninstall/restore), ad-blocking DNS, firewall, tweaks, biometric lock.
- **v0.2:** permissions, battery, users, cleaner, app optimisation, change log + recover.
- **v0.3:** photo/video cleanup, duplicates, risk audit.
- **Later:** polish, localisation, F-Droid / IzzyOnDroid listing.

## 7. Build and release

- GitHub Actions: JDK 17, Gradle 8.x, AGP 8.x; `assembleRelease` with R8; APK uploaded as an artifact.
- Signing key stored as repository secrets so updates install over each other.
- Install and update with ObtainX from this repo's releases. Target APK size roughly 3-6 MB (estimate, unverified).

## 8. Known limits and open questions

- Shizuku must be restarted after every reboot (wireless debugging needs Wi-Fi).
- Firewall rules and some tweaks reset on reboot.
- Unconfirmed: automatic re-apply on Shizuku start without a background service.
- Unconfirmed on this ROM: `pm trim-caches`, `sm fstrim`, `cmd package hide`, `dumpsys netstats` output. Each is verified in aShell You before being coded.
- Trinity Engine (CPU/RAM Vitalization) cannot be replicated; the app must not break it (no force-stops, keep Oplus scheduling services enabled).
- No custom ad-block lists without a resolver the user controls (NextDNS / own AdGuard Home).

## 9. License

See `LICENSE`.
