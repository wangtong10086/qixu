# Changelog

## 0.5.0-alpha4

- Add per-application splash adapters for QQ Music, Zhihu, JD, Xiaohongshu, Amap, bilibili and Taobao, with local status reporting and reversible Vector scope changes.
- Restore Zhihu 11.11.1 compatibility and replace exact-version gating with signing-identity and complete entrypoint checks.
- Pause an installation after two eligible startup crashes or ANR exits within five minutes; preserve the policy switch and expose UI/CLI retry.
- Add screen-off cached-process cleanup with protected-task exemptions, explicit per-app force-stop, and visual background-service management with restoration journals.
- Document host tests, device fixtures and acceptance separately: Zhihu/Taobao behavior was manually accepted; automatic crash-suspension fault injection remains unverified on device.

## 0.4.1

- Flush atomic state files and parent directory metadata before reporting persistence success.
- Scope resource restoration records to the current boot; preserve the existing journal format on upgrade.
- Supervise the local root daemon and restart it after unexpected exit; explicit stop/recovery suppresses restart.
- Clarify that management uses a local Unix socket, remove the offline-network icon and refresh disconnected UI connections while resumed.

## 0.4.0

- Replace visible revision numbers with named configuration switching; create copies and retain independent edits, atomically with the active configuration.
- Add network rate switches and presets with explicit bit/s units and drop/burst semantics.
- Document the current kernel CPU bandwidth limitation and the prerequisites for enabling real time quotas without flashing a kernel.

- Replace settings JSON dumps with capability statuses, chronological event records and per-UID network counters linked to application icons.
- Replace raw configuration editing with configuration management and structured automation/push forms; retain CLI JSON import/export.
- Use the actual page ancestry for toolbar and system back gestures, including nested configuration pages and unsaved-edit confirmation.
- Keep revision-checked writes, typed rule validation and existing policy fields intact.

## 0.3.1

- Remove development, design and implementation commentary from the overview, settings and editors; retain actionable guidance and errors.

## 0.3.0

- Search application names, package IDs, declared/granted permissions and effective policy keywords from applications and policies.
- Add Chinese permission aliases and matching permission evidence; retain the distinction between permission grants and AppOps access.
- Visualize real app processes as foreground, visible, background or cached with process counts and UID activity markers.
- Refresh running snapshots every five seconds while a discovery page is resumed; preserve and label stale results on connection failure.
- Load real application icons on demand in lists, search results and application detail titles, with bounded image caching.
- Expose read-only `apps.index`, `apps.running` and `app.icon` protocol commands, plus `search` and `running` CLI commands.

## 0.2.0

- Rename the UI to 栖序 / Qixu and add adaptive and monochrome launcher icons.
- Adopt the native-simple Material 3 layout with overview, applications, policies and settings.
- Add policy forms, inheritance controls, save impact summaries and revision conflict handling.
- Preserve the existing package ID, root service, CLI and policy format.
- Publish build, install, protocol, licensing and contribution documentation, plus host CI.

## 0.1.0

- Introduce the Vector API 102 hook, authenticated root service, CLI, policy core and Compose manager.
- Add UID resource controls, BPF network limits, typed automation, push proxy and restricted legacy migration.
