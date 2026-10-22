# 策略与控制协议

所有功能通过同一个 root 服务协议控制，界面不另设策略存储。`fuxictl rpc 'JSON'` 可访问全部命令。
速率为十进制 bit/s，内存为 MiB。当前针对 Android 16 主用户与 Vector API 102。

| 字段 | 可选值及含义 |
| --- | --- |
| `autostart` | `allow` / `deny`；禁止后台冷启动、接收器、服务与作业，不阻止用户打开活动 |
| `background` | `normal` / `balanced` / `restricted`；restricted 使用 Android UID idle、待机桶和安全的缓存冻结 |
| `cpuNice` | 0 不调整；1–19 为后台线程优先级下限，不是 CPU 百分比 |
| `cpuQuota` | `periodUs`、`foregroundUs`、`backgroundUs`（微秒）；0 阶段额度为关闭，`allowProtected` 显式允许关键应用配额；见 [CPU 配额](cpu-quota.md) |
| `manualCleanup` | 独立的一键清理规则，默认仅回收缓存；见 [控制协议](protocol.md#manual-cleanup-and-cpu-quota-schema-3) |
| `processes` | 按完整进程名配置前后台资源限制与活动任务豁免；见 [资源管理](resources.md) |
| `uclampMax` | 1–1024；调度利用率上限，不是硬 CPU 时间配额 |
| `memoryHighMiB` | 0 不限制；非零触发 cgroup 内存回收压力 |
| `memoryMaxMiB` | 0 不限制；硬上限，可能触发应用自身 OOM |
| `network` | `allow` / `background-deny` / `deny` |
| `uploadBps` / `downloadBps` | 0 不限速；正数为速率上限，加上 `burstBytes` 的初始突发额度 |
| `permissions` | Android 危险运行时权限，`deny` 或 `system`；system 退出本管理器的拒绝策略 |
| `appOps` | Android 操作名映射到 `allow` / `ignore` / `deny` / `foreground` / `default` |
| `wakeLocks` | 要拒绝的完整 WakeLock tag 数组 |
| `resident` | 申请系统进程常驻，并阻止本模块路径中的缓存冻结 |
| `cleanupOnTaskRemoved` | 划掉任务后延迟回收缓存进程；前台、活动下载/音乐/导航和音频焦点拥有者豁免 |
| `protected` | 显式覆盖关键服务保护；默认由系统身份、角色与必要服务决定 |

默认模板即时影响所有仍继承默认的普通应用。逐应用仅存例外，`app inherit` 删除该应用全部例外。
受保护应用获得宽松基线；显式的逐应用设置仍可以覆盖它。共享 UID 的资源、权限与 AppOps 会一起生效，
冲突的逐应用规则会被拒绝，`explain` 显示规则来源。运行时权限变化会触发重新核对。

前台、活动任务和音频使用暂时豁免后台 CPU/内存约束；网络固定速率上限始终有效。
`restricted` 服从 Android 的充电等系统待机豁免，不伪造电池状态。

## 命令协议

| 命令 | 功能 |
| --- | --- |
| `status` / `capabilities` | 服务、钩子、快照版本、执行错误与内核能力 |
| `apps` / `app.get` / `explain` | 应用清单、最终策略、例外与执行状态 |
| `defaults.get` / `defaults.patch` | 默认模板 |
| `app.patch` / `app.replace` / `app.inherit` | 修改部分例外、完整替换例外、恢复继承 |
| `config.get` / `config.replace` | 备份与导入完整配置；`expectedRevision` 可阻止覆盖并发编辑 |
| `mode.set` | observe / enforce / disabled |
| `rules.get` / `rules.replace` | 类型化自动化，不执行任意导入脚本 |
| `migration.import` | 校验旧备份并导入到观察模式，支持 `dryRun` |
| `network.stats` | 按 UID 查询通过、丢弃的包数和字节数 |
| `audit` / `runtime.reconcile` | 审计或立即重新执行策略 |

变更命令支持 `dryRun:true`。`config.replace` 可管理逐应用 `pushProxy`：
`enabled`、`showContent`、`startOnPush`、`skipIfRunning`、`sound`、`vibrate`，未设置的值为 false。
代理消息正文只在 system_server 内存中处理，不进入服务审计；审计保留代理结果等状态元数据。

`revision` 是服务配置版本，`hookRevision` 是系统钩子已接收的版本。配置保存与系统消费是两个步骤；
应核对二者及 `lastError`，不能只以命令退出成功判断全部钩子已执行。

## 恢复

```sh
adb shell su -c 'sh /data/adb/fuxicontrol/recover.sh'
```

恢复脚本停止本服务、关闭本应用的开机启动和 Vector 激活，恢复仍由本服务管理的内存、调度、
隐私和待机设置，并撤下自身网络程序。其他模块和 Thanox 不受此脚本修改。重启后卸载系统钩子。
重新启用需要启动本服务、恢复本应用的 Vector 激活及 system/0 作用域，然后重启。
私有签名目录必须独立备份，丢失签名将影响以后覆盖升级。
