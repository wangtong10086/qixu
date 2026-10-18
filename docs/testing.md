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
CPU quota testing is not reported as passed: this kernel lacks CFS bandwidth support; see
[cpu-quota.md](cpu-quota.md).

0.4.0 device acceptance: UDP upload 512 kbit/s and IPv6 download 2 Mbit/s met the
wire-byte ceiling including the configured 64 KiB burst, with nonzero drop counters.
TCP upload measured 448,920 bit/s and download 1,996,296 bit/s at the same ceilings.
Both suites restored the pre-test policy; the probe was uninstalled. Profile navigation
returned to the correct overview/settings parent on right/left edge gestures. All 16
system hooks were ready in enforce mode with no service error after installation.
