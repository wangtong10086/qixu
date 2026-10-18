# Vector API provenance

`api-src/io/github/libxposed/api` is the modern API used by Vector 2.2 build 3080,
from JingMatrix/libxposed-api commit `39cac0845771547c9c67a3e3ce255af110a54a0e`.
Its Apache 2.0 license is included in `api-src/LICENSE`.
`InternalApi` and `SinceApi` are from libxposed annotation 1.0.0 source artifacts.

Run `tools/build_api.py` with JDK 17 to compile these sources against Android 36.
The AndroidX annotation compile dependency is downloaded with a pinned SHA-256.
The generated API jar is compile-only and is not shipped inside the application.
No legacy `de.robv.android.xposed` API is used.

Upstream: https://github.com/JingMatrix/libxposed-api
