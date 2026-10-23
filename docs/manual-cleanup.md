# 后台清理

首页“一键清理”、长按栖序图标的快捷方式和系统快捷设置磁贴执行同一操作：
按照当前配置筛选后台应用，直接强制停止，无确认。设置页可添加桌面快捷图标和磁贴。
长按磁贴进入设置。锁屏触发须先解锁。

一键清理保留 `manualCleanup` 的关闭状态、后台时长、CPU 和内存阈值；命中的
`cached` 与 `force-stop` 规则均执行强制停止。原有“清理规则与预览”的执行按钮及
`cleanup preview/run` 命令仍保留规则本身的力度，不更改配置文件。
CPU 阈值需要现有连续 30 秒样本；样本不足会跳过，不等待、不估算。

应用列表“操作”和应用资源页提供“强制停止”；进程列表和详情提供“结束进程”。
单项操作忽略批量筛选阈值，普通后台应用不确认。音乐、导航、下载、前台服务、
用户保留规则触发确认；确认只覆盖这一次，不修改规则。系统和关键服务、当前可见应用
始终保护。共享 UID 不允许整包强停；归属不明确的进程不能结束。

强制停止可能暂停应用后续通知，直至重新打开。结束进程不设置整包停止状态；
Android 或应用可能重新拉起进程，栖序不会追杀新实例。

## CLI

在已授权的 Root shell 中执行 `/data/adb/fuxicontrol/fuxictl`：

```sh
fuxictl cleanup quick --dry-run
fuxictl cleanup quick
fuxictl cleanup app com.example.app
fuxictl process list com.example.app
fuxictl cleanup process com.example.app 1234 87654321
fuxictl cleanup app com.example.app --override-protection
fuxictl cleanup status
```

`PID` 和 `START_TICKS` 使用进程快照里的 `pid`、`startTicks`。结束前校验 AMS 归属，
再通过 pidfd 固定进程并核对 UID、启动时间，防止 PID 重用误杀。
`--override-protection` 只对单项音乐／前台任务／用户保留有效，不能覆盖关键服务保护。

写请求立即返回任务 ID。`cleanup status` 查询执行进度与逐项结果：
`running` / `completed` / `failed`；目标结果为 `exited`、`requested`、`skipped`、
`confirmation-required`、`failed`。`requested` 仅表示系统操作已接受，不冒充已退出。
同时只执行一个任务，重复点击返回当前任务。任务运行期间配置变化时跳过未执行项。
服务断开时不自动重放命令。观察／停用模式不能执行。

## Binder 请求

新增 `cleanup.quick`（可带 `dryRun`）、`cleanup.app`（`package`）、
`cleanup.process`（`package`、`pid`、`startTicks`）。单项可带 `overrideProtection`。
现有 `cleanup.preview`、`cleanup.run`、`cleanup.status` 保持兼容。
GUI 与 CLI 都通过现有认证 Binder 通道访问；桌面快捷目标 Activity 不导出，
由系统 ShortcutManager / LauncherApps 以所属应用身份启动。磁贴只接受系统绑定。

不需要新模块或内核。更新后打开栖序一次即可发布长按快捷方式。
