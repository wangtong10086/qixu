# Local control protocol v1

Implementation: `Wire.java`, `RootDaemon.java`, `Commands.java`, `Store.java`, `Policy.java`.

## Transport and authentication

Android abstract UNIX stream socket: `fuxicontrol.v1`. UTF-8 JSON, one request object followed by `\n`; one response object per normal connection. This is not HTTP/OpenAPI or a network port.

- UID 0: full commands.
- Manager UID: package `dev.local.fuxicontrol` and signing digest matching the root-owned `manager.sha256`.
- UID 1000: only `watch`, `event`, `status`; this is a trusted system UID boundary, not authentication of a particular hook class.
- Others: connection closed. Clients verify the server peer is UID 0.

Server permits 16 simultaneous connections, including watchers. Read timeout is 10 seconds; normal `Wire.call` timeout is 15 seconds. The current parser caps a request at 1,048,576 Java UTF-16 code units (the implementation's error text says “1 MiB”; it is not a byte-accurate limit). Authentication/parse errors can close the socket without an error object.

## Normal request / response

```json
{"command":"status"}
```

```json
{"ok":true,"result":{"service":true,"mode":"observe","revision":4,"schema":1}}
```

The sample result is abbreviated. Command execution errors use:

```json
{"ok":false,"error":"IllegalStateException: Configuration changed; reload before saving"}
```

There is no stable numeric error code. `Wire.call` unwraps `result`; the CLI prints that result rather than the transport envelope. `Wire.CommandException` distinguishes a received command rejection from an I/O failure in the manager.

## Commands

All names below are literal `command` values. `package` is an Android package name, `uid` is an integer.

| Command | Additional request fields | Result |
| --- | --- | --- |
| `status`, `capabilities` | none | Runtime/hook status or kernel capabilities |
| `apps` | none | `{apps:[...]}` with package, UID, label, system and protected flags |
| `apps.index` | optional `query` (default empty), `scope` (default `all`) | `{apps:[...],revision,capturedAtElapsedMs}` including requested permissions, effective policies and lastUpdateTime |
| `apps.running` | none | `{apps:{package:{state,importance,processes,activeTask,foregroundService}},capturedAtElapsedMs,systemProcessCount}` |
| `app.icon` | `package` | `{png:base64}` with a 128 × 128 PNG loaded through PackageManager |
| `config.get` | none | Full policy object |
| `defaults.get` | none | Default policy fields |
| `app.get`, `explain` | `package` | revision, effective, overrides, runtime |
| `defaults.patch` | `patch` | Mutation result |
| `app.patch`, `app.replace` | `package`, `patch` | Merge fields / replace complete application overrides |
| `app.inherit` | `package` | Remove application overrides |
| `config.replace` | `config` | Validate and replace full policy |
| `mode.set` | `mode`: observe / enforce / disabled | Change execution mode |
| `rules.get` | none | `{rules:[...]}` |
| `rules.replace` | `rules` | Replace typed automation rules |
| `migration.import` | `directory` (device path) | Import verified supported backup into observe mode |
| `audit` | none | Recent audit records |
| `network.stats` | optional `uid` (default -1) | Native network counters |
| `runtime.reconcile` | none | Reapply current runtime state and return status |

Policy mutations accept optional `expectedRevision` and `dryRun`. UI always supplies the loaded revision. Other writers **should also supply it**; omission intentionally permits an unconditional write. A mismatch is rejected before mutation. Reload and review edits instead of automatically retrying an old full config.

```json
{"command":"app.replace","package":"com.example.app","patch":{"uploadBps":512000},"expectedRevision":4,"dryRun":true}
```

Mutation result: `{dryRun:boolean, config:{...}}`. A successful dry run preflights the candidate and returns the proposed next revision, but does not persist it or advance the service revision. `runtime.reconcile` and hook `event` are runtime operations, not dry-run policy edits.

Config persistence and runtime execution are separate: runtime reconciliation can fail after a config has been saved. On an error, reread `config.get` and `status` before attempting another write. Do not assume an error implies no state was committed.

## Policy schema

Root fields: `schema:1`, `revision`, `mode`, `defaults`, `apps`, `rules`, `pushProxy`. The service owns persisted revision increments. See [policy.md](policy.md) for fields and units. See [examples](../examples/README.md) for synthetic values.

Rules contain `id`, `enabled`, `event` (`foreground-left` or `push-arrived`), `package`, `action` (`record` or `kill-cached`) and `delayMs` (0–86400000).

Push proxy entries are keyed by package. Supported boolean fields are `enabled`, `showContent`, `startOnPush`, `skipIfRunning`, `sound`, `vibrate`; absent values are false.

## Internal hook channel

`{"command":"watch"}` changes the connection into a stream of raw snapshot objects, one per new revision; these are **not** wrapped in `{ok,result}`. Snapshot fields include revision, defaults, overrides, effective per-package policies, application entries (including protection flags), rules, mode and push-proxy state, as implemented by `Catalog.snapshot`.

`event` is an internal trusted hook ingress, not a public automation injection API. Its event names and fields follow `ModuleEntry`, `PushProxy` and `RuntimeEngine.event`; third-party clients must not fabricate system lifecycle events. Normal CLI/UI integrations should use the commands above.

## Compatibility

The v1 address and schema are retained for existing installations. New optional fields must not silently change the meaning of existing values. Breaking changes require an explicit schema/protocol migration and updated fixtures. Root storage paths and the historical Fuxi package ID are compatibility identifiers, not the current product name.

## Application discovery (0.3)

These commands are read-only and require root or the authenticated manager, like `apps`.
`apps.index` is separate from the lightweight catalog/watch stream so permission metadata does not inflate hook traffic.
Each permission contains `name`, localized `label` and `granted`, from `PackageInfo.REQUESTED_PERMISSION_GRANTED`.
This is a package permission grant, not proof that AppOps, special access or current foreground conditions allow use.
The index is freshly read on refresh/resume/navigation, not polled with the running list. Install/update changes invalidate the existing catalog; UI icons use `lastUpdateTime` in their cache keys.

Scopes: `all` (name + permissions + effective policy), `name`, `declared`, `granted`, `policy`.
Whitespace-separated terms are case-insensitive AND conditions over the selected text, with literal substring matching.
Chinese aliases cover common permissions such as 相机、定位、麦克风; raw permission names also work.
`policy` searches canonical effective policy fields and Chinese summaries such as 禁止自启、限速、常驻.
It describes configured effective rules, not proof that every runtime action succeeded; consult `status.lastError`.

```sh
fuxictl search CAMERA declared
fuxictl search CAMERA granted
fuxictl search 'example 定位' all
fuxictl search 限速 policy
fuxictl running
fuxictl rpc '{"command":"app.icon","package":"com.example.app"}'
```

Running membership uses `ActivityManager.getRunningAppProcesses()` and each process's actual `pkgList`.
It does not mark every shared-UID sibling as running, and does not infer liveness from installed or configured app counts.
The catalog covers the current service user's installed application UIDs; this is not a list of every native/system process or all Android users.
Each app is grouped by its most important process: importance ≥ 400 is cached, ≤ 100 foreground, ≤ 200 visible except importance 125 (foreground service), otherwise background.
“Foreground” is Android's importance class, not necessarily the app owning the focused window.
`foregroundService` means at least one process has foreground-service importance; it is not a complete inventory of every service component.
`activeTask` is a UID-level hook flag, including shared-UID semantics. Cached processes need not be executing or consuming CPU.
`systemProcessCount` counts the API's returned process entries, not displayed apps. Apps with no known package membership are omitted.
Timestamps use device elapsed realtime, not Unix time. Snapshots are inherently point-in-time.

Icons load only for visible UI items, with three concurrent requests and a 64-image LRU cache (128 × 128 pixels each).
The UI stops launching new running polls when paused or on another page; an in-flight socket call remains bounded by its existing timeout.
Failed polls retain the previous snapshot with an explicit warning, rather than displaying an empty running list.

### Named configuration profiles

`profile.create` takes `name`, duplicates the current configuration and activates the copy.
`profile.activate` takes `id`; obtain IDs from `config.get.profiles`. Supply `expectedRevision`
for both commands. `dryRun` validates without persisting. Profiles include mode, defaults,
application overrides, rules and push settings. Normal writes update the active profile.
Profile snapshots and the active policy share one atomic `policy.json` transaction; internal
revision checks remain in use even though the UI no longer displays revision numbers.
Inactive profiles are validated when imported; activation also performs runtime preflight.
The existing `config.get` / `config.replace` commands support full backup and restore.
