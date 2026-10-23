# Testing

## Host / CI

Run the commands in [CONTRIBUTING.md](../CONTRIBUTING.md). Coverage includes default inheritance, role protection, validation, typed legacy rules, UI numeric/list normalization and native token-bucket arithmetic. Android compilation/lint does not validate a ROM's private interfaces or BPF attachment.

## Explicit device tests

These scripts are not an automatically safe test suite. Inspect prerequisites and side effects before use:

| Script | Scope |
| --- | --- |
| `test_qixu_ui.py` | Current UI read-only walkthrough; requires unlocked phone, running service, design-preview installed, default autostart deny |
| `test_qixu_ui.py --write-preview` | Also changes the design-preview application's rule, then restores it; not for concurrent policy editing |
| `test_design_ui.py` | Isolated synthetic preview UI |
| `test_cli.py`, `test_integration.py` | Observation-mode desired-state and lifecycle fixtures |
| `test_network.py`, `test_tcp.py` | Network controller and disposable traffic fixtures |
| `test_recovery.py` | Disruptive service recovery exercise; use a dedicated test setup |
| `test_ui.py`, `test_ui_write.py` | Historical 0.1 UI fixtures; not acceptance tests for the 0.2 UI |

Use `ANDROID_SERIAL` to select a target. Device tests are never run in CI. Keep logs, policy exports and screenshots under ignored private paths. Existing public design comparison screenshots use synthetic in-app data, not real device policy exports.

Verify both desired configuration and runtime state. When testing writes, back up first, target only disposable packages, restore in `finally`, compare contents excluding the monotonic revision, then check `lastError` and hook readiness. Don't overwrite unrelated concurrent edits to force a test to pass.

The generalized first-install path no longer requires a Thanox backup. This publication pass tested clean builds and host checks; it did not reset the maintained phone to repeat a fresh service installation.


## Discovery UI (0.3)

`python3 tools/test_discovery_ui.py` is read-only: it verifies CAMERA declared/granted sets, UI search counts, real manager PID membership, list/detail/search icons and unchanged policy.
Screenshots stay under ignored `private/discovery-acceptance/` because application inventories are personal data.
`--preview-lifecycle` additionally starts/stops `dev.local.fuxicontrol.design`; install the disposable `design-preview` APK beforehand. The option does not install/uninstall it or change its policy.
The running view is a process-importance snapshot, not a CPU-use measurement. Cached processes are counted separately.

## Settings and back gestures (0.4)

`python3 tools/test_settings_ui.py` requires an unlocked, gesture-navigation device with the manager foreground and no concurrent phone use. It opens settings sections, checks that raw JSON is absent, performs actual swipes from both screen edges, verifies nested parent destinations, and discards default-template, automation and push drafts. It compares all policy contents before/after and stores private captures under `private/settings-acceptance/`. It does not save policy changes or install a fixture app.

Host tests cover parent destination selection, rule delay normalization/rejection and traffic units. Visual diagnostics do not establish that every hook works on other ROMs, and network counters cover controlled traffic rather than all device traffic.

Named-profile validation: host tests cover independent edits, duplicate names and inactive-profile
validation. Device acceptance covers creating a copy, switching back, dry-run writes and rejecting
an outdated revision. Network probes now retain ordinary application policies and change only the
throwaway probe UID; both UDP and TCP tests restore the original configuration in `finally`.
At the 0.4 stage CPU quota testing was unavailable. The later paired-kernel/v1 UID acceptance is documented in [cpu-quota.md](cpu-quota.md); its process-subquota and isolated-UID limits still apply.

0.4.0 device acceptance: UDP upload 512 kbit/s and IPv6 download 2 Mbit/s met the
wire-byte ceiling including the configured 64 KiB burst, with nonzero drop counters.
TCP upload measured 448,920 bit/s and download 1,996,296 bit/s at the same ceilings.
Both suites restored the pre-test policy; the probe was uninstalled. Profile navigation
returned to the correct overview/settings parent on right/left edge gestures. All 16
system hooks were ready in enforce mode with no service error after installation.

For local-service recovery acceptance on a USB-connected test phone, run
`python3 tools/test_service.py`. It temporarily turns off Wi-Fi and mobile data,
checks RPC while offline, kills only the controller daemon to test recovery, verifies
explicit stop remains stopped, restores the previous radio switches, and compares the
policy before/after. Do not run while the phone is needed for network activity.


## Splash and background management (0.5)

- `test_splash.py --package com.zhihu.android|com.taobao.taobao|tv.danmaku.bili` requires an already enabled, compatible adapter and an idle unlocked device. It starts/stops only the selected target, compares enabled/disabled interception, and restores the ad switch in `finally`. A run without a real new hit is not reported as successful blocking.
- `test_splash_ui.py --app-label 知乎|淘宝|哔哩哔哩` checks the status page, icons and detail navigation. It launches the manager; captures remain private.
- `test_splash_health.py` intentionally crashes a disposable probe twice. Build app release/probe debug first, set `JAVA_HOME` to JDK 17 and configure `sdk.dir` in `local.properties`. It validates production exit-history processing in an isolated store, not the production Binder endpoint end to end. This device test has **not yet completed**; unit coverage is separate.
- `test_background_services.py`, `test_service_stop.py`, `test_screen_cleanup.py`, and `test_cached_cleanup.py` change only disposable probe behavior and restore its policy. Screen tests may lock the device. `test_background_ui.py` checks component-list navigation.

See [splash acceptance](splash-ads.md) and [background acceptance](background-management.md) for exact evidence and limitations. Never run all device scripts as an unattended suite.

## Resources, cleanup and CPU quotas (0.6)

- `test_cpu_kernel.py` is a host-only unittest for configuration preparation; run `python3 -m unittest discover -s tools -p test_cpu_kernel.py`. It does not flash a kernel.
- `test_process_resources.py` uses the disposable probe to verify CPU/memory/process-network controls and restores its policy.
- `test_v1_cpu_quota.py` requires a paired kernel with CFS bandwidth enabled and changes probe CPU groups; inspect prerequisites first. It is never run by CI.
- Native token-bucket checks, core/app unit tests, compilation and lint can run without a phone. Binder runtime, system hooks and actual cloud delivery remain device-specific acceptance.

## Cleanup shortcuts and foreground recovery (0.7)

- `test_manual_cleanup.py` changes only the disposable probe policy and restores it. It verifies foreground protection, stale process identity, child termination, whole-app stop and protected-task confirmation. Requires the probe APK and an idle test device.
- `test_manual_cleanup_ui.py` checks app/process actions and navigation using the probe. It performs real termination; keep the device unlocked and do not use other applications during the run.
- `test_foreground_latency.py` measures foreground quota/affinity restoration with a disposable probe under calibration load. Read its options first; results include ADB observation overhead, not frame timings.
- `fixtures/IpcStress.java` is a root `app_process` fixture for read-only Binder saturation and concurrent status requests. It deliberately consumes the session limit; use only on an idle test setup.

These are explicit device tests, not CI discovery targets. Keep captures private and restore policies after testing. Global bulk termination and shortcut/tile click flows have separate acceptance boundaries recorded in [validation](validation.md).
