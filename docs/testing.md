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
