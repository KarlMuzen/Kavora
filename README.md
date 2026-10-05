# Shizuku Phone Manager

Lightweight, privacy-first replacement for Realme/Oplus Phone Manager, built on [Shizuku](https://shizuku.rikka.app/). One app instead of six: app control, debloat, firewall, permissions, ad-blocking DNS, cleanup and tweaks.

> Status: **blueprint only** (no code yet).

## 1. Principles

- **No `INTERNET` permission.** The app cannot phone home. Verifiable in the manifest.
- **No analytics, ads, Firebase or Google dependency.**
- **No always-running service, no accessibility service, no VPN.** Runs only while open (idle cost = 0 CPU, 0 heat). Automation hooks (section 4, Automation) are event-triggered and never keep a service alive.
- **Minimal dependencies:** Kotlin, Jetpack Compose (Material 3), Shizuku API. Nothing else. R8 shrink on release.
- **Fixed command allow-list.** No free-form shell input; every action is a typed command with validated arguments.
- **Everything reversible.** Destructive actions need confirmation, show the exact command first, and are logged locally so they can be undone.
- **Local storage only** (SharedPreferences, plus JSON export/import through the system file picker). No cloud, no accounts.
- **Offline data only.** Anything the app "knows" (package descriptions, DNS presets) is bundled in the APK.
- **Honest persistence.** Every action is labelled "survives reboot" or "resets on reboot". The app saves its desired state on every change so it can restore what a reboot wipes.
- Own app lock via BiometricPrompt is **optional** (no always-on watcher); see roadmap.

## 2. Tools this replaces

The goal is to uninstall these, one release at a time.

| Tool | What it is used for | Covered by | Release |
| ---- | ------------------- | ---------- | ------- |
| Hail | Freeze / hide / suspend apps | Apps | v0.1 |
| Canta | Debloat with package descriptions and presets | Apps | v0.1 |
| ShizuWall | No-VPN per-app firewall | Firewall | v0.1 |
| App Ops | Per-app permissions and appops | Permissions | v0.2 |
| Realme Phone Manager | Dashboard, battery, optimisation | Dashboard, Battery, Optimisation | v0.2 to v0.3 |
| SD Maid SE | Cleanup, leftover data, duplicates | Cleaner (partial) | v0.3 |
| Shizuku | Privileged backend | Stays; this app depends on it | n/a |

SD Maid SE is deep and well tested. Keep it until the Cleaner covers what you actually use from it.

## 3. Feature map (Realme Phone Manager -> this app)

| Realme feature | Decision | Implementation (via Shizuku shell; verify each on device) |
| -------------- | -------- | --------------------------------------------------------- |
| Storage cleanup / App data clean-up | Build | `pm trim-caches`, `pm clear <pkg>` (per-app, confirmed). Shell-based, so no accessibility automation |
| Leftover data of uninstalled apps | Build (new) | Compare `Android/data`, `Android/media`, `Android/obb` folder names with `pm list packages -u`; risk-tagged, moved to local trash |
| App management | Build | `pm disable-user --user 0`, `pm enable`, `pm uninstall -k --user 0` |
| Freeze modes (Hail) | Build (new) | Disable, hide (`pm hide`), suspend (`pm suspend`); each app is unfrozen by the same mode it was frozen with |
| Debloat (Canta) | Build (new) | Bundled package descriptions and safety badges, presets |
| Hide apps | Build | `pm hide` if permitted, else disable-user |
| Recover system apps | Build | `cmd package install-existing --user 0 <pkg>` + local change log |
| App permission | Build | `pm grant/revoke`, `cmd appops set` (replaces App Ops), templates |
| Multiple users | Build | `pm list users`, `pm remove-user <id>` (warn: deletes clone data) |
| Data usage | Build | Per-app stats (`dumpsys netstats`) + firewall |
| Battery management | Build | temps (`dumpsys battery`, `thermalservice`), `dumpsys deviceidle whitelist`, `am set-standby-bucket`, `appops RUN_ANY_IN_BACKGROUND` |
| System boost | Reinterpret | Real actions only: dexopt, TRIM, animation scale, background restriction. No fake "boost" |
| App optimisation | Reinterpret | Dexopt control per app (`cmd package compile`). Installed apps cannot be compressed, so there is no "compress apps" button |
| Trinity engine | Partial | CPU/RAM Vitalization are kernel/OS-level, not replicable. ROM side approximated: `cmd package compile -m speed-profile -a`, `sm fstrim` / idle-maint, duplicate finder |
| Viruses and risk / App security control / Payment protection | Reinterpret | Local risk audit, no signature scanner (needs network): accessibility services, overlay apps, device admins, unknown installers, risky permission combos. Play Protect stays |
| Photo cleanup / Video cleanup | Reinterpret | Largest files + exact duplicates (size, partial hash, full hash). No AI similarity |
| App lock | Drop | Needs an always-on watcher. Keep Realme's |
| Private safe | Drop | Use Cryptomator |
| Theft protection | Drop | Needs network + account. Google Find Hub already covers it |
| Emergency SOS | Drop | Keep OS feature |

Added (not in Phone Manager): **freeze modes and tags**, **debloat descriptions**, **ad-blocking DNS**, **firewall**, **profiles**, **automation hooks**, **tweaks**, **reboot detector with one-tap restore**, **all-in-one backup/restore**, **changes since last visit**, **privacy audits**.

## 4. Modules

### Dashboard

Battery level/temp, skin/CPU temp, RAM and swap, storage. Read-only (`dumpsys battery`, `dumpsys thermalservice`, `/proc/meminfo`, `df`).

### Apps

Searchable list with filters (user / system / disabled / removed), and a user selector (default user 0; see Users).

- **Freeze modes:** disable, hide or suspend. Freeze, unfreeze, uninstall for user, restore, clear cache/data.
- **Tags:** group apps ("work", "games") and freeze or unfreeze a whole tag in one tap. Whitelisted apps in a tag are skipped.
- **Debloat info:** bundled, offline description and safety badge per system package (check the license and attribution of any list that is bundled).
- **Presets:** save and share a set of apps as a local JSON file; import with the system file picker.
- **No force-stop by default** (it fights Trinity's long background retention).

### Ad-blocking DNS

One-tap Private DNS via `settings put global private_dns_mode hostname` + `private_dns_specifier <host>`.

- Presets: `base.dns.mullvad.net` (default; ads, trackers, malware), `adblock.dns.mullvad.net`, `dns.adguard-dns.com`, custom hostname (e.g. a NextDNS ID), and Off/Cloudflare (`one.one.one.one`).
- After switching, check connectivity and **auto-revert on failure**. The check runs through the Shizuku shell (for example `ping`), because the app itself has no `INTERNET` permission.
- Limits (shown in-app): domain-level only, cannot remove in-app ads served from content domains; the provider sees DNS lookups; an active VPN may override Private DNS.
- Persists across reboots.

### Firewall (no VPN)

Per-app network block using Android's chain-3 mechanism, the same approach as ShizuWall: `cmd connectivity set-chain3-enabled true|false`, `cmd connectivity set-package-networking-enabled true|false <pkg>`.

- Rules are cleared on reboot (Android behavior). The app stores the desired rule set and restores it with one tap (see Reboot detector and restore).
- Works alongside a VPN (does not use the VPN slot).
- Chain 3 blocks or allows an app entirely; it cannot separate Wi-Fi from mobile data.
- To test: `cmd netpolicy` background-data restrictions per app may survive reboots, which would allow a persistent "block background data" option.
- Firewall suggestions: apps that hold `INTERNET` but have not been opened in N days (usage stats read through the shell).

### Reboot detector and restore

Shizuku has to be restarted after every reboot, and some changes (firewall rules, some tweaks) reset with it. The app cannot save state "just before" a reboot without a running service, so it saves on every change and detects the reboot afterwards.

- **Write-through state:** the desired state (firewall rules, tweaks, anything labelled "resets on reboot") is saved each time it changes, in device-protected storage, together with a boot-relative timestamp (the same idea ShizuWall uses).
- **Detector:** on open, a changed boot time means a reboot happened. Affected items are marked "not applied" instead of showing stale "on" flags.
- **One-tap restore:** a banner such as "Reboot detected: 8 items need re-applying" with a preview and a single button. Needs Shizuku running; otherwise it shows a short "start Shizuku" checklist.
- **Optional boot notification (open question):** an opt-in "Start Shizuku, then tap to restore" notification would need a boot receiver. It is short-lived and not a service, but it bends the no-background principle, so it stays off until decided.
- Items that survive reboot (disable, uninstall for user, Private DNS) are never in the restore list.

### Permissions

Per-app permission and appop viewer/editor.

- **Templates:** save a set of appops and apply it to many apps.
- **Drift check:** compare the stored template with the current appop values and highlight what Android reset or synced. No background monitor (it would need a foreground service).

### Profiles

A profile is a saved set of actions: freeze or unfreeze tags, a firewall block list, an appop template, a DNS preset. Examples: "Work", "Sleep". Applied manually, from a shortcut/tile, or from an intent. Never scheduled. A profile can also be exported as a shell script (for `rish`, Termux or a PC), so it can run without opening the app.

### Automation

Home-screen shortcuts, Quick Settings tiles and exported intent actions (for Tasker/MacroDroid) for: apply profile, freeze/unfreeze app or tag, firewall on/off.

- **Intents are off by default** and require a user-set token extra, because an exported receiver can be called by any app.
- Only actions from a fixed allow-list; no free-form commands.
- Needs Shizuku running; reports an error otherwise.

### Optimisation

Per-app dexopt control with a before/after size readout:

- Smaller, slower launch: lower compile filter (`verify`) or reset to default (`--reset`).
- Balanced: `speed-profile` (default suggestion).
- Faster, bigger: `speed` or `everything`.
- Cleanup of stale compiled artifacts (`pm art cleanup`, if the ROM has it).

Compile filter names vary by Android version; read the on-device help first. Per-app with confirmation. "Compile all" is manual only, with a heat/time warning. Never scheduled.

### Cleaner

Cache trim, per-app data clear, leftover-data finder, large files, duplicates, TRIM. Never scheduled; always manual.

- **Leftover data:** folders of apps that are no longer installed, risk-tagged (app data is the highest risk). Apps removed with `pm uninstall -k` are restorable, so they are not leftovers.
- **Duplicates:** group by size, then partial hash, then full hash only for matches. Deleted items go to a local trash folder first.

### Tweaks

Animation scales, phantom-process fix (`settings_enable_monitor_phantom_procs`, `max_phantom_processes`), Doze whitelist for Shizuku, "re-apply all" button.

### Users

List profiles (e.g. the Realme clone-apps profile, user 999) and remove them with a data-loss warning. Apps, Permissions and Firewall get a user selector; check per command which ones accept a user argument.

### Backup and restore

One-tap, all-in-one export of the data the user has built up in the app, as a JSON file through the system file picker (no storage permission).

- **The user picks what to include:** firewall rules, frozen and removed apps, tags, presets, profiles, appop templates, DNS, tweaks, change log.
- **Optional passphrase encryption** using the platform's built-in crypto (no extra dependency), because an app list is personal data.
- **Import is data, not commands:** check the format version and package names, map entries to typed `Command`s, keep the protected-package list enforced, and show a preview ("will freeze 12 apps, block 8") before applying.
- **Not the same as re-apply:** the reboot restore handles what a reboot resets; export is for a new phone, a factory reset or recovery.
- Uninstall-for-user changes are lost on a factory reset, so a restore can re-apply a debloat list from the export.

### Risk audit

Local heuristics only, listed with the reason for each flag. No verdicts, no cloud lookups.

### Changes since last visit

On open, compare the current packages and permissions with a local snapshot from the last visit: new installs, updates that added permissions, and removed or frozen system apps that came back (for example after an OTA). No service; the diff runs only when the app is opened.

### Privacy audits (read-only, on demand)

- Last access time per app for sensitive ops (camera, microphone, location), read from appops. No background monitor.
- Wake report: which apps hold wakelocks, alarms and jobs (`dumpsys power`, `alarm`, `jobscheduler`), for finding battery drain.
- Each report states what was found and why. No verdicts.

## 5. Safety rules

- Protected packages are never freeze/uninstall targets: `com.android.systemui`, `com.android.launcher`, `com.android.phone`, `com.android.settings`, `com.google.android.gms`, `com.android.vending`, `moe.shizuku.privileged.api`, `com.oplus.battery`, `com.oplus.athena`, `com.oplus.nas`, `com.oplus.exsystemservice`, `com.mediatek.*`, `com.android.providers.*`.
- Oplus/Realme packages show a caution tag; unknown packages are never removed in bulk.
- **Command preview:** every action shows the exact command before it runs.
- Every change is written to a local change log so it can be undone from the Recover screen.
- Automation intents are opt-in, token-protected and limited to the allow-list.
- Imported backups and presets are validated and previewed; they can never bypass the protected-package list.
- Reboot reverts firewall rules; a reboot is the universal escape hatch.

## 6. Architecture

- Single Gradle module, package `io.github.karlmuzen.phonemanager`, minSdk 30, targetSdk 34.
- Kotlin + Compose Material 3, single Activity, ViewModel per screen, no DI framework, no network or image libraries.
- `ShizukuShell`: wraps Shizuku permission and process execution. Only a sealed `Command` type can be run, so there is no arbitrary shell input. Each `Command` validates its arguments (for example package-name format) and can describe itself as the exact shell string for the preview.
- Each module is a small class that builds `Command`s and parses their output.
- Settings and logs in SharedPreferences; no database. Desired state is written on every change, with the reboot marker in device-protected storage. Backups and presets are JSON via the system file picker; optional backup encryption uses the platform's `javax.crypto`. Bundled package descriptions live in an asset file.

## 7. Roadmap

Each release should let you uninstall at least one of the tools in section 2.

- **v0.1 (retires Hail, Canta, ShizuWall):** Shizuku connect/permission, Apps (freeze modes, tags, uninstall/restore, bundled descriptions), ad-blocking DNS, firewall, reboot detector with one-tap restore, persistence labels, change log (recording).
- **v0.2 (retires App Ops, most of Phone Manager):** permissions + templates, dashboard, battery, tweaks, users, profiles (with script export), automation hooks, recover screen, all-in-one backup/restore, changes since last visit, privacy audits (last access, wake report), optional biometric lock.
- **v0.3 (partly retires SD Maid SE):** cleaner (cache, leftover data, large files, duplicates), optimisation (dexopt), risk audit, firewall suggestions.
- **Later:** component blocker (disable individual services and receivers; risky, needs the protected list and per-component confirmation), storage analyzer, optional lossy media compression (originals kept), device profiles for non-Realme phones, localisation, F-Droid / IzzyOnDroid listing.

## 8. Build and release

- GitHub Actions: JDK 17, Gradle 8.x, AGP 8.x; `assembleRelease` with R8; APK uploaded as an artifact.
- Signing key stored as repository secrets so updates install over each other.
- Install and update with ObtainX from this repo's releases. Target APK size roughly 3-6 MB (estimate, unverified); the bundled description list adds to it.

## 9. Known limits and open questions

- **Biggest limit: Shizuku must be restarted after every reboot, and starting it without root needs wireless debugging, which needs a Wi-Fi connection.** The app cannot fix this. As far as known, the network does not need internet access, so another device's hotspot may work; test on the target ROM. Some Shizuku forks keep wireless debugging on so Shizuku can restart itself; that can be a docs tip, not a dependency.
- Firewall rules and some tweaks reset on reboot. The app cannot save state just before a reboot (no service), so it saves on every change, detects the reboot on next open and offers one-tap restore. Fully automatic re-apply is not possible without Shizuku running.
- Chain-3 firewall commands: ShizuWall lists Android 11+; confirm on this device.
- Android 10+ may reset or sync appops. Verify that permission templates persist on this ROM before promising it.
- Unconfirmed on this ROM: `pm trim-caches`, `sm fstrim`, `pm hide`, `pm suspend`, `pm art cleanup`, `cmd package compile` filter names, `dumpsys netstats` output, `cmd netpolicy` background restrictions and whether they persist, appop last-access output, listing `Android/data` through the shell. Each is verified in aShell You before being coded.
- Trinity Engine (CPU/RAM Vitalization) cannot be replicated; the app must not break it (no force-stops, keep Oplus scheduling services enabled).
- Installed apps cannot be compressed; optimisation means dexopt control only.
- No custom ad-block lists without a resolver the user controls (NextDNS / own AdGuard Home).
- Cleaner is not a full SD Maid SE replacement (no system-cleaner filters, scheduler or app-definition database).
- Disabling individual app components can cause restart loops, which is why the component blocker stays in "Later".

## 10. Prior art

- [Hail](https://github.com/aistra0528/Hail): freeze modes, tags, shortcut and intent automation.
- [Canta](https://github.com/samolego/Canta): debloat badges and presets.
- [ShizuWall](https://github.com/AhmetCanArslan/ShizuWall): no-VPN firewall via chain 3, tiles and intents.
- App Ops (Rikka): per-app appops and templates.
- [SD Maid SE](https://github.com/d4rken-org/sdmaid-se): corpse finder, deduplicator, storage analysis.

## 11. License

See `LICENSE`.
