# Contributing

Start with [build.md](docs/build.md) and [architecture.md](docs/architecture.md). Use an issue to discuss significant behavior or protocol changes; small bug fixes can go directly to a pull request.

## Local checks

Build the vendored API and native artifacts, then run:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :probe:assembleDebug :design-preview:assembleDebug
cc -Wall -Wextra -Werror native/budget_test.c -o /tmp/qixu-budget-test
/tmp/qixu-budget-test
python3 -m compileall -q tools
python3 -m unittest discover -s tools -p test_cpu_kernel.py
```

CI runs host checks and compilation, not root/device tests. Device fixtures can change application policy or restart services. Read each script and `docs/testing.md` first; do not run the entire `tools/test_*.py` collection blindly.

## Changes

- Keep changes focused. Preserve CLI/Binder compatibility and configuration inheritance unless a migration is included.
- Validate untrusted input at the boundary; internal failures must remain visible. Avoid silent fallbacks and broad exception handling.
- Use UTF-8, LF and four-space indentation. Kotlin follows the official style; retain existing Java/C style when editing nearby code.
- Add regression tests for policy, authentication, restoration and real boundary cases. Document the device/ROM and what was actually measured.
- Update protocol documentation and examples when changing the wire format. Current policy schema is 4; retain documented schema 1/2/3 migrations and never reinterpret old values silently.
- Never commit private configs, device identifiers, APK dumps, signing keys, tokens or personal screenshots. Use synthetic examples.

PR descriptions should explain the original problem, resulting behavior and validation, with limitations. Contributions are submitted under the license applicable to the modified file; preserve third-party notices. No CLA is currently required.
