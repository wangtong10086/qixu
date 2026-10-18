# Changelog

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
