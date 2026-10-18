# Security policy

Qixu is experimental privileged software. Only the current main branch is maintained; there is no security support SLA.

Report authentication bypasses, arbitrary code execution, unsafe restoration or private-data exposure through GitHub's **Report a vulnerability** feature for this repository. Do not include tokens, real policy exports, account data or device identifiers in public issues. If private reporting is unavailable, open an issue requesting a private contact without publishing exploit details.

## Boundaries

- The root daemon authenticates UNIX peer credentials. UID 0 has full access; the manager requires its package UID and configured signing digest. UID 1000 is trusted for hook events, status and snapshots only.
- Root and system compromise are outside this boundary. This is not a sandbox against other root applications.
- The endpoint is a local Android abstract UNIX socket, not a TCP service.
- Config revisions prevent stale writes when the caller supplies `expectedRevision`. CLI scripts must use it for concurrent writes.
- Source builds need their own signing identity. The maintainer's local signing key is not shipped.
- Imported legacy automation is validated against supported templates and converted to typed actions; arbitrary MVEL/shell imports are not executed.

See [protocol.md](docs/protocol.md) for actual authentication and error behavior. See [install.md](docs/install.md) for recovery. Do not test disruptive changes on banking, messaging or system packages; use the disposable probe.
