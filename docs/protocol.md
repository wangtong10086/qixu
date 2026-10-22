# Local control protocol v1

Implementation: `Wire.java`, `RootDaemon.java`, `Commands.java`, `Store.java`, `Policy.java`.

## Transport and authentication

AIDL `IController` is discovered through `content://dev.local.fuxicontrol.ipc` in the separate `:ipc` process. Only UID 0 can register the root endpoint. The provider is Direct Boot aware and stores no policy.

- UID 0: full commands.
- Manager UID: package `dev.local.fuxicontrol` and signing digest matching root-owned `manager.sha256`.
- UID 1000: only `watch`, `event`, `status`.
- Other callers: only the splash channel, limited to a supported package owned by their UID; reports additionally match the watching PID.

Caller UID/PID is captured in the Binder transaction before dispatch. Discovery is public; possession of an endpoint does not authorize management commands.
`open(request, lifetime)` returns a reliable anonymous pipe. Responses remain newline-delimited JSON, avoiding Binder's response-size limit. `watch` streams snapshots on config changes; Binder death or `cancel(lifetime)` releases the worker. No network connection or listening socket is involved.
Requests are capped at 262144 UTF-16 code units. There are 48 active slots, at most 8 per UID. Normal pipe reads time out after 15 seconds of inactivity; Binder discovery/open is not covered by that pipe timeout. Writes are never automatically retried, since a timed-out command may have executed. Re-query state before another write. Reconnection discovers a new endpoint; system and splash watchers subscribe again through their existing watch lifecycle.

## Normal request / response

```json
{"command":"status"}
```

```json
{"ok":true,"result":{"service":true,"mode":"observe","revision":4,"schema":4}}
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
| `services.list` | `package` | `{package,revision,protected,services:[...]}`; declared and running component state |
| `service.policy` | `package`, `class` (full name), `mode`: block / system | Mutation result; reversible component override |
| `service.stop` | `package`, `class`, optional `dryRun`, `expectedRevision` | `{stopped,package,class}` or dry-run result; protected tasks rejected |
| `ads.status` | none | Signing identity, cross-version entrypoint status, connections, hits and suspension |
| `ads.retry` | `package`, optional `dryRun` | `{package,dryRun,retry:true}`; clear per-install health state without changing policy |
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

Root fields: `schema:4`, `revision`, `mode`, `defaults`, `apps`, `rules`, `pushProxy`, `scheduling`. The service owns persisted revision increments. See [policy.md](policy.md) for fields and units. See [examples](../examples/README.md) for synthetic values.

Rules contain `id`, `enabled`, `event` (`foreground-left` or `push-arrived`), `package`, `action` (`record` or `kill-cached`) and `delayMs` (0–86400000).

Push proxy entries are keyed by package. Supported boolean fields are `enabled`, `showContent`, `startOnPush`, `skipIfRunning`, `sound`, `vibrate`; absent values are false.

## Internal hook channel

`{"command":"watch"}` changes the connection into a stream of raw snapshot objects, one per new revision; these are **not** wrapped in `{ok,result}`. Snapshot fields include revision, defaults, overrides, effective per-package policies, application entries (including protection flags), rules, mode and push-proxy state, as implemented by `Catalog.snapshot`.

`event` is an internal trusted hook ingress, not a public automation injection API. Its event names and fields follow `ModuleEntry`, `PushProxy` and `RuntimeEngine.event`; third-party clients must not fabricate system lifecycle events. Normal CLI/UI integrations should use the commands above.

## Compatibility

The historical package ID and data directory are retained for existing installations. Binder discovery uses `dev.local.fuxicontrol.ipc` with protocol 1; policy schema 4 migrates schema 1/2/3 and their named profiles before use. New optional fields must not silently change the meaning of existing values. Breaking changes require an explicit schema/protocol migration and updated fixtures. Root storage paths and the historical Fuxi package ID are compatibility identifiers, not the current product name.

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
The UI stops launching new running polls when paused or on another page; an in-flight Binder/pipe call remains bounded by its existing timeout.
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


### Splash compatibility and retry

`ads.status` retains its existing fields. `matched` now means accepted signing identity, not an exact version match. Installed entries add `updated`, `inspectedVersion`, `crossVersion`, `suspended`, and `suspendReason`. Actual method resolution and interception remain separate `report.state` values (`ready`, `blocked`, `unsupported`, `error`). A `healthy` channel report updates persisted health only and does not overwrite the visible last result.

`ads.retry` accepts `package` and optional `dryRun`; root and the signed manager retain their normal write permissions. It clears only that installation's startup breaker and recent report. It does not change policy revision, mode, ad switch, or profile. Reopen the target app after retry. The CLI form is `fuxictl ads retry PACKAGE [--dry-run]`.

## 0.6 资源与进程接口

- `resources.snapshot`：整机与应用／进程快照，采样时间、ageMs、error、networkAvailable；请求会续期十秒前台采样。
- `resources.snapshot.system` 新增 `swapTotalBytes`（字节），与 `swapUsedBytes` 一起记录到历史；旧历史缺少总量时不推算。总量为 0 表示未启用 Swap。
- `resources.detail`：package、可选 process；返回 PSS、主线程调度、内存限额与当前阶段／豁免。
- `resources.history`：scope=system/app/process、identity、since（Unix 毫秒），返回样本与最早记录时间。
- `resources.settings`：可选 history 布尔值；读取或改变历史记录。
- `resources.clear`：清空资源历史，不修改应用策略。
- `process.policy.patch`：package、process、rule；替换该进程的两阶段规则。
- `process.policy.inherit`：package、process；删除额外进程规则。
- `apps.batch.patch`：packages 数组、patch 资源字段；原子保存，未给出字段保留。

后三项支持 expectedRevision 与 dryRun；沿用保存后执行机制，执行错误须通过状态检查，不能把配置保存当作内核已生效。schema 3 增加 CPU 配额和手动清理，schema 1/2 导入自动迁移所有方案。完整语义见 resources.md。

## Manual cleanup and CPU quota (schema 3)

- `cleanup.preview` → `{id, revision, createdElapsedMs, mode, apps}`. Starts a 45-second measurement window, returns eligibility and skip reasons.
- `cleanup.run` requires `id` and `expectedRevision`. Returns a job; preview expires in 60 seconds and can only be consumed once.
- `cleanup.status` returns `{id, state, results, error}`. Never replay `run` to poll.
- CLI: `fuxictl cleanup preview`, `fuxictl cleanup run ID REVISION`, `fuxictl cleanup status`.
- `cpuQuota`: `{periodUs:100000, foregroundUs:0, backgroundUs:0}`. Applies to defaults/app patches and process phase resource patches. Zero disables; CPU time and period are microseconds. Shared UID policies must agree.
- `manualCleanup`: `{mode:"cached", backgroundSeconds:60, cpuPercent:0, rssMiB:0}`. `cpuPercent` is the device-wide normalized 30-second average; zero ignores that threshold. Global `force-stop` is rejected.
- Configured quota is separate from runtime `cpuQuota.state` / `applied` / `reason` / `cpuStat`. A pending layout is not enforcement.


### CPU quota on a protected application

`cpuQuota` accepts optional boolean `allowProtected` (default false). Explicitly enabling it permits the UID budget despite protected/resident cleanup status; startup, network and active-task exemptions are unchanged. Shared-UID applications must agree on this flag and all quota values.

The v1 backend reports `backend: cgroup-v1-uid`, `actual: "quota_us period_us"`, `cpuStat`, and `reassignedThreads`. `waiting-process` means no process exists to bind; isolated UIDs report `pending-layout`. Foreground/off restores owned membership. Android migration is event-driven with a five-second reconciliation interval; `applied` describes the observed kernel group, not a zero-latency migration guarantee. See [CPU quota](cpu-quota.md) for measured low-budget overhead.

## 0.7 核心调度与文件配置接口

当前策略为 schema 4，传输协议仍为 1。新增接口均复用 root／签名管理器授权和现有版本校验。

| command | 请求字段 | 返回／行为 |
| --- | --- | --- |
| `cpu.topology` | 无 | possible、online、cores 容量和频率、groups |
| `cpu.status` | package | 当前阶段、方案上限、线程匹配来源与内核读回 |
| `threads.list` | package、process | 当前线程、名称、TID、身份、CPU 区间比例及最近核心 |
| `scheduling.patch` | patch | 合并方案级 backgroundSmallCoresOnly |
| `app.apply` | package、patch | 对象递归合并，数组替换；不改变 app.patch 的浅合并合同 |
| `app.unset` | package、field | 删除应用顶层覆盖 |
| `thread.policy.put` | package、process、rule | 按 id 替换／追加有序线程规则 |
| `thread.policy.remove` | package、process、id | 删除指定规则 |

写入返回额外的 `changes` 数组：path 是 JSON Pointer，before／after 是原值和新值。dryRun 额外返回 `schedulingPreview`，包含预检应用数、当前可见线程数、受限／无交集数量及匹配来源计数；这是预检采样，不执行绑核。配置保存与内核生效仍然分开。CLI 自动添加读取时版本，显式 RPC 维持原来的可选版本语义。

`scheduling` 随命名方案保存，`cpuAffinity` 按阶段继承，线程字段与恢复语义见 [调度指南](scheduling.md)。完整文件、ADB 和冲突处理范例见 [CLI 手册](cli.md)。
