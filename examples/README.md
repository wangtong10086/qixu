# Synthetic examples

`policy.observe.json` is an observation-mode default policy with no personal package list. `app-limit.request.json` is a dry-run request. Replace the example package with a disposable installed application and `expectedRevision` with the value from `config.get` before using it.

Rates are decimal bit/s, not bytes/s. Do not treat these files as a recommendation to restrict critical applications. Importing a full policy replaces rules and exceptions; back up and inspect it first.

## 核心调度

- `app-affinity.json`：用于 `app apply PACKAGE FILE` 的应用例外对象。替换内部 com.example.app 进程名后使用。
- `thread-affinity.json`：用于 `thread apply PACKAGE PROCESS FILE` 的单条线程规则。
- 所有对象由实际策略校验器测试；详见 [CLI 手册](../docs/cli.md)。
