# Synthetic examples

`policy.observe.json` is an observation-mode default policy with no personal package list. `app-limit.request.json` is a dry-run request. Replace the example package with a disposable installed application and `expectedRevision` with the value from `config.get` before using it.

Rates are decimal bit/s, not bytes/s. Do not treat these files as a recommendation to restrict critical applications. Importing a full policy replaces rules and exceptions; back up and inspect it first.
