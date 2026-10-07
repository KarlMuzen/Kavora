# Design spec: Kavora

Companion to `README.md`. Scope: Phase 0 to 2 (core + v0.1).
**[verify]** = assumption to confirm in Phase 0.1 and record in `docs/command-notes.md`.

## 1. Layout

```
app/src/main/
  aidl/io/github/karlmuzen/phonemanager/shell/IShellService.aidl
  kotlin/io/github/karlmuzen/phonemanager/
    core/   Pkg.kt Command.kt Plan.kt Allow.kt Guard.kt Executor.kt
    shell/  ShellClient.kt ShellService.kt ShizukuState.kt
    state/  StateStore.kt ChangeLog.kt Boot.kt Caps.kt
    mod/    apps/ firewall/ dns/      # each: Module.kt, ViewModel.kt, Screen.kt
    ui/     theme/ nav/ ActionHost.kt PreviewSheet.kt
  assets/debloat.json
docs/       command-notes.md design.md
```

Manifest: `QUERY_ALL_PACKAGES` (without it the app list is filtered on Android 11+) and `ShizukuProvider` as in the Shizuku-API README. Nothing else.

## 2. Shell layer

### 2.1 AIDL

```aidl
package io.github.karlmuzen.phonemanager.shell;
import android.os.ParcelFileDescriptor;

interface IShellService {
    void destroy() = 16777114;                              // reserved by Shizuku
    String execBatch(String requestJson) = 1;               // small outputs
    ParcelFileDescriptor execStream(in String[] argv) = 2;  // large stdout (dumpsys, pm list -f)
}
```

Request: `{"stopOnError":false,"cmds":[{"id":"c1","argv":["pm","disable-user","--user","0","com.x"],"timeoutMs":15000}]}`
Response: `{"results":[{"id":"c1","exit":0,"out":"...","err":"","truncated":false,"ms":84}]}`

JSON in a String keeps the AIDL free of Parcelables (no extra plugin).

### 2.2 Service rules (layer 2; the typed `Command` is layer 1)

- Bound with `Shizuku.bindUserService`, `daemon(false)`, version = `BuildConfig.VERSION_CODE`. `destroy()` calls `exitProcess(0)`.
- Run `ProcessBuilder(argv)`, never `sh -c`. Pipes and filtering happen in Kotlin.
- `argv[0]` must be in `Allow.bins`: pm, cmd, settings, dumpsys, am, sm, wm, ping, getprop, id. For `cmd`, `argv[1]` must be in `Allow.cmdServices`: package, appops, connectivity, netpolicy, deviceidle. `Allow.kt` is shared by app and service.
- Per-command timeout; 200 KB cap per stream; whole reply under ~600 KB (binder buffer is about 1 MB, shared). Over the cap sets `truncated`; the client re-runs via `execStream` (service writes into a `ParcelFileDescriptor.createPipe()` on a thread).

### 2.3 Client state

```kotlin
sealed interface ShizukuState {
    data object NotInstalled : ShizukuState   // moe.shizuku.privileged.api absent
    data object Stopped : ShizukuState        // !Shizuku.pingBinder()
    data object NoPermission : ShizukuState   // checkSelfPermission != GRANTED
    data object Binding : ShizukuState
    data class Ready(val shell: IShellService, val uid: Int) : ShizukuState // 2000 shell, 0 root
    data object Died : ShizukuState           // binder-death listener fired
}
```

`ShellClient` exposes `StateFlow<ShizukuState>` (listeners: `addBinderReceivedListenerSticky`, `addBinderDeadListener`, `addRequestPermissionResultListener`). Any state other than `Ready` disables mutating actions and shows the start checklist. Put `ShellClient` behind an interface so tests use a fake.

## 3. Command model

```kotlin
@JvmInline value class Pkg private constructor(val v: String) {
    companion object {
        private val RE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
        fun of(s: String) = if (RE.matches(s)) Pkg(s) else null
    }
}
enum class Persist { SURVIVES, RESETS_ON_REBOOT, READ_ONLY }

sealed class Command {
    abstract val argv: List<String>           // what runs
    abstract val persist: Persist
    open val touches: Set<Pkg> = emptySet()   // input to Guard
    open val inverse: Command? = null         // null on a write = not undoable, extra confirm
    open val destructive = false              // clear data, remove user
    fun describe() = argv.joinToString(" ")   // preview text only, never executed as a string
    open fun ok(r: ExecResult) = r.exit == 0
}

data class Disable(val p: Pkg, val u: Int = 0) : Command() {
    override val argv = listOf("pm", "disable-user", "--user", "$u", p.v)
    override val persist = Persist.SURVIVES
    override val touches = setOf(p)
    override val inverse get() = Enable(p, u)
}
```

v0.1 command set (`P` = package, `U` = user):

| Command | argv | Persist | Inverse | Verify (one batched read per plan) |
| --- | --- | --- | --- | --- |
| Disable | `pm disable-user --user U P` | survives | Enable | P in `pm list packages -d` |
| Enable | `pm enable --user U P` | survives | Disable | P not in `-d` |
| Hide | `pm hide --user U P` | survives | Unhide | not in default list, in `-u` |
| Unhide | `pm unhide --user U P` | survives | Hide | in default list |
| Suspend | `pm suspend --user U P` | survives | Unsuspend | `FLAG_SUSPENDED` set |
| Unsuspend | `pm unsuspend --user U P` | survives | Suspend | flag clear |
| UninstallForUser | `pm uninstall -k --user U P` | survives (lost on factory reset) | InstallExisting | not in default list, in `-u` |
| InstallExisting | `cmd package install-existing --user U P` | survives | UninstallForUser | in default list |
| ClearData | `pm clear --user U P` | n/a | none (destructive) | `Success` |
| SetChain3 | `cmd connectivity set-chain3-enabled true\|false` | resets | opposite | [verify] |
| FwBlock / FwAllow | `cmd connectivity set-package-networking-enabled false\|true P` | resets | each other | [verify] get command |
| DnsSet | `settings put global private_dns_specifier H`, then `private_dns_mode hostname` | survives | snapshot | `settings get` |

Reads (`READ_ONLY`, no mutex, no preview, no log): `ListPackages(flags, U)`, `Get(setting)`, `Help(bin)`, `Ping(host)`.

Rule: every write has an `inverse` or is `destructive`. Snapshot inverses (DNS) are built at apply time from a before-probe.

## 4. Execution pipeline

```
intent -> Module.plan() -> Plan(label, steps, stopOnError)
       -> Guard.check(plan)        BLOCK: refuse, CAUTION: extra confirm
       -> PreviewSheet             describe() lines + persistence tag
       -> Executor.apply(plan)     mutex held
            1 before-probes        only where an inverse needs a snapshot
            2 shell.execBatch      all steps, one round trip
            3 verify               re-read; exit 0 alone is not proof
            4 ChangeLog.append     do, undo, per-step status
            5 StateStore write     only for verified steps
       -> Undo snackbar(changeId)
```

- Partial failure: keep successes, report failures per row. Bulk plans use `stopOnError=false`; dependent sequences (DNS) use `true`.
- Undo is a new `Plan` built from the logged inverse. It goes through Guard and Executor and is logged as an undo of entry N.
- One `ActionHost` at Activity scope owns the preview sheet and undo snackbar, so every screen reuses them.
- Imports, presets, profiles, tag actions and intents all become `Plan`s, so they all pass the same Guard.
- JVM tests (no device): golden tests asserting exact `argv` per `Command`, Guard rules, restore-list calculation, import validation, executor against a fake shell.

### Guard

Verdict per (command, package): BLOCK > CAUTION > OK.

- **BLOCK:** README static list (globs) plus a runtime set computed on connect:
  - default home (resolve `CATEGORY_HOME`), IME (`Settings.Secure.DEFAULT_INPUT_METHOD`), dialer (`TelecomManager.defaultDialerPackage`), SMS (`Telephony.Sms.getDefaultSmsPackage`), WebView (`WebView.getCurrentWebViewPackage()`), active device admins, this app, Shizuku
  - apps with `FLAG_PERSISTENT`, or `sharedUserId` starting with `android.uid.`
- **CAUTION:** `com.oplus.*`, `com.coloros.*`, `com.heytap.*`, `com.realme.*`, expert-badged entries, any `destructive` command.
- Bulk plans skip packages missing from the debloat list unless explicitly selected.

## 5. State

| Store | Where | Format | Written |
| --- | --- | --- | --- |
| desired | device-protected prefs `state` | JSON | every verified change |
| change log | `filesDir/changes.jsonl` | JSONL, capped at 2000 | every applied plan |
| capabilities | prefs `caps` | JSON | on connect if fingerprint changed |
| settings | prefs `settings` | key/value | on change |
| debloat info | `assets/debloat.json` | JSON | build time |

`desired`:

```json
{
  "v": 1,
  "firewall": { "chain3": true, "blocked": { "com.example.a": { "boot": 57 } } },
  "frozen":   { "com.example.b": { "mode": "DISABLE", "user": 0 } },
  "tags":     { "games": ["com.example.b"] },
  "dns":      { "preset": "mullvad-base", "pendingRevert": null }
}
```

Change-log line:

```json
{"id":1042,"ts":1791158400000,"boot":57,"label":"Freeze tag games",
 "do":[["pm","disable-user","--user","0","com.example.b"]],
 "undo":[["pm","enable","--user","0","com.example.b"]],
 "status":"applied"}
```

Rules:

- The device is the truth for display. `desired` is only used for restore.
- Every `RESETS_ON_REBOOT` item stores `boot` = boot id at its last verified apply.
- **Boot id:** `Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)` [verify it increments on this ROM]. Fallback: `currentTimeMillis() - elapsedRealtime()`, compared with +-2 min tolerance for clock sync.
- **Restore list** = items where `persist == RESETS_ON_REBOOT && item.boot != bootNow`.
- Restore is one `Plan` (preview, Guard, batch). On success set `boot = bootNow`.
- A Shizuku restart without a reboot leaves the boot id unchanged, so nothing is flagged.

## 6. Capability probes

Non-destructive presence checks, cached under `Build.FINGERPRINT + uid`, re-run after an OTA.

| Capability | Probe | Pass if |
| --- | --- | --- |
| pm.hide, pm.suspend, pm.trimCaches, pm.art | `pm help` | subcommand listed |
| pkg.installExisting, pkg.compile | `cmd package help` | subcommand listed |
| fw.chain3 | `cmd connectivity help` | `set-chain3-enabled` and `set-package-networking-enabled` listed |
| net.policy | `cmd netpolicy help` | exit 0 |
| sm.fstrim | `sm help` | `fstrim` listed |
| boot.count | `settings get global boot_count` | integer |

A failed probe hides the feature ("not available on this ROM"). A passed probe means the command exists, not that it behaves; behaviour goes in `command-notes.md`.

## 7. v0.1 modules

### 7.1 Apps

State detection for user U [verify]:

| Source | How |
| --- | --- |
| visible (enabled or disabled) | `pm list packages --user U` |
| disabled | `pm list packages -d --user U` |
| incl. hidden / removed | `pm list packages -u --user U` |
| suspended | `ApplicationInfo.FLAG_SUSPENDED` via PackageManager |
| system / user | `-s` / `-3` |

```
in -u, not in default list -> HIDDEN if state.frozen[p].mode == HIDE, else REMOVED
in -d                      -> DISABLED
FLAG_SUSPENDED             -> SUSPENDED
otherwise                  -> ENABLED
```

- Unfreeze uses the mode stored in `frozen[p]`. If the app was frozen elsewhere, infer the mode from the derived state.
- Tag freeze: `Plan("Freeze 'games'", members.filter { !whitelisted && verdict != BLOCK }.map { Disable(it) })`. One preview, one batch.
- Load the three package lists in parallel on `Dispatchers.IO`; labels cached; icons in an `LruCache<String, ImageBitmap>` sized by bytes (about 8 MB) and loaded lazily per visible row.

### 7.2 Firewall

- Enable chain 3 in its own batch first (`stopOnError=true`), then send the block batch (`stopOnError=false`).
- Row states: `ALLOWED`, `BLOCKED`, `BLOCKED_STALE` (desired blocked, `boot != bootNow`, shown amber).
- Actual state: `cmd connectivity get-package-networking-enabled P` if the probe finds it [verify]; otherwise desired plus boot id.
- Restore: `SetChain3(true)` plus all stale blocks in one batch.
- Gate the whole module on `fw.chain3`. Chain 3 is likely newer than minSdk 30 [verify].

### 7.3 DNS

One plan, `stopOnError=true`:

```
0 snapshot   settings get global private_dns_mode ; ... private_dns_specifier
1 persist    state.dns.pendingRevert = {mode, specifier}     (before touching anything)
2 settings put global private_dns_specifier <host>
3 settings put global private_dns_mode hostname
4 wait 2 s ; ping -c 1 -W 3 <probe-domain>
5 resolved   -> clear pendingRevert, log
  unresolved -> write snapshot back, report failure
```

- Host regex: `[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+`, max 253 chars. "Off" = `private_dns_mode off`, specifier untouched.
- Pass = name resolved, even with 100% packet loss (ICMP may be blocked). Fail = "unknown host" style error [verify exact strings and exit codes].
- Confirm the resolver cache is flushed when Private DNS changes [verify]; otherwise rotate probe domains.
- If the app dies between steps 1 and 5, the next open shows "DNS change unconfirmed [Revert] [Keep]" from `pendingRevert`.

## 8. UI state (v0.1)

```kotlin
enum class AppState { ENABLED, DISABLED, HIDDEN, SUSPENDED, REMOVED }
enum class Rule { ALLOWED, BLOCKED, BLOCKED_STALE }

data class AppRow(val pkg: Pkg, val label: String, val system: Boolean,
                  val state: AppState, val tags: Set<String>, val verdict: Verdict)
data class AppsUi(val rows: List<AppRow> = emptyList(), val query: String = "",
                  val filter: Filter = Filter.USER, val user: Int = 0,
                  val selected: Set<Pkg> = emptySet(), val loading: Boolean = true)
data class FirewallRow(val pkg: Pkg, val label: String, val rule: Rule)
```

- Each ViewModel combines its data flow with `ShizukuState` into one immutable UI state.
- Home banners, in order: Shizuku card; reboot restore (restore list non-empty); DNS unconfirmed (`pendingRevert != null`).
- Preview sheet: label, first 5 `describe()` lines then "+N more", persistence tag, caution badge, Cancel / Run. After running: Undo snackbar (changeId).
- Home vitals use SDK APIs (`BatteryManager`, `ActivityManager.MemoryInfo`, `StatFs`) so Home works with Shizuku stopped; shell only for thermal.

## 9. Phase 0.1 verification script

Run in aShell You or `rish`. `D` = a throwaway app you do not care about. Test on it before any system app.

```sh
# env
id; getprop ro.build.version.sdk; getprop ro.build.fingerprint
settings get global boot_count
# presence (non-destructive)
pm help; cmd package help; cmd connectivity help; cmd netpolicy help; sm help
# behaviour
pm disable-user --user 0 $D; pm list packages -d --user 0 | grep $D; pm enable --user 0 $D
pm hide --user 0 $D; pm list packages --user 0 | grep $D; pm list packages -u --user 0 | grep $D; pm unhide --user 0 $D
pm suspend --user 0 $D; pm unsuspend --user 0 $D
pm uninstall -k --user 0 $D; pm list packages -u --user 0 | grep $D; cmd package install-existing --user 0 $D
# firewall
cmd connectivity set-chain3-enabled true
cmd connectivity set-package-networking-enabled false $D    # open D: network gone?
cmd connectivity get-package-networking-enabled $D          # exists? output format?
cmd connectivity set-package-networking-enabled true $D
# DNS probe semantics
ping -c 1 -W 3 example.com; echo $?
ping -c 1 -W 3 nonexistent.invalid; echo $?
settings get global private_dns_mode; settings get global private_dns_specifier
```

Reboot matrix: set each of (a) disable-user (b) hide (c) suspend (d) uninstall -k (e) chain-3 block (f) Private DNS (g) one appop, then reboot, restart Shizuku, re-read. Also check `boot_count` increased.

`docs/command-notes.md` row format: `| command | sdk | works / differs / n/a | stdout sample | survives reboot |`

## 10. Phase 0.3 decisions (recommended)

- **Boot notification:** no. The banner on open is enough; revisit only if reboots become a daily annoyance.
- **Biometric lock:** drop for now. Realme's app lock stays.
- **Debloat list:** the UAD-NG list is, I believe, GPL-3.0 [verify]. For private use either way works. If you ever publish, license the app GPL-3.0 and credit it, or write your own list for the packages you actually remove.

## 11. Changes to README

1. Add `QUERY_ALL_PACKAGES`.
2. Commands are argv lists; `describe()` is display-only.
3. Protected list becomes static plus runtime (section 4).
4. Reboot detection uses `BOOT_COUNT` with per-item `boot` stamps instead of one global marker.
5. Firewall is gated by a capability probe, not assumed.
6. Home vitals use SDK APIs; the shell is only for thermal.
