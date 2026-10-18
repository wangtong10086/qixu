# Licenses and third-party provenance

Project-authored source defaults to Apache-2.0 (`LICENSE`). This does not replace the licenses of vendored components.

| Component | Source / version | License and distribution |
| --- | --- | --- |
| Qixu userspace code, UI and tools | This repository | Apache-2.0 |
| `native/network.bpf.c` | This repository | GPL-2.0-only; `LICENSES/GPL-2.0-only.txt` |
| `native/network.h`, `native/budget.h` | This repository | Apache-2.0 OR GPL-2.0-only; SPDX headers allow the BPF build to use GPL-2.0-only |
| Modern Xposed API | JingMatrix/libxposed-api commit `39cac0845771547c9c67a3e3ce255af110a54a0e` | Apache-2.0; `vendor/api-src/LICENSE`; compile-only, not bundled in APK |
| libxposed annotations | 1.0.0 source artifacts | Apache-2.0; see `vendor/README.md` |
| Lucide vector paths | lucide-icons/lucide tag `0.468.0` | ISC, with the upstream Feather/MIT attribution retained; `design-preview/LUCIDE-LICENSE` and APK `assets/lucide-license.txt` |
| Gradle wrapper | 8.13 distribution | Apache-2.0; generated launcher and wrapper JAR |
| AndroidX / Compose Material 3 | Versions in Gradle files | Apache-2.0; resolved through Google Maven |
| Kotlin | 2.2.10 | Apache-2.0; resolved through Maven |
| org.json JSON-java | 20240303, test/compile-only | Public Domain per published POM; device runtime uses Android org.json |
| JUnit | 4.13.2, test-only | EPL-1.0; not bundled in APK |

The original Qixu launcher icon is project-authored; it is separate from the Lucide interface icons. Preserve this notice, vendored notices and the ISC notice when redistributing source or APKs. If distributing the BPF object, also provide its corresponding source and build files under the stated GPL license.

Upstream references:
- https://github.com/JingMatrix/libxposed-api
- https://github.com/libxposed/annotation
- https://github.com/lucide-icons/lucide/tree/0.468.0
- https://github.com/gradle/gradle/tree/v8.13.0
