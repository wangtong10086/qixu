# CPU 时间配额：当前内核调查与实现前提

本次只读调查：Xiaomi 13 的内核为 `5.15.207-g03e6e48a5b4e`，Clang 21，
`CONFIG_CGROUP_SCHED=y`、`CONFIG_FAIR_GROUP_SCHED=y`，但
`CONFIG_CFS_BANDWIDTH` 未启用。CPU controller 挂载在 cgroup v1 的 `/dev/cpuctl`，
不存在 `cpu.cfs_quota_us` / `cpu.cfs_period_us`。本次没有修改或刷写启动分区。

因此当前版本不承诺 CPU 时间硬配额。nice 调整竞争优先级，uclamp 调整调度利用率约束，
两者都不能限制一个周期内消耗的总 CPU 时间。也不使用 SIGSTOP/SIGCONT 循环模拟：
这种方式可能暂停持锁线程，并破坏 Binder 和前台切换时序。

## 内核改动

必须找到与 `03e6e48a5b4e` 构建对应的完整源码、补丁和构建配置；版本字符串不能证明
某个公开分支与正在运行的镜像相同。本地项目尚未提供对应内核源码，因而未生成可刷入镜像。

在匹配的源码上保留现有配置，并添加以下配置 fragment：

```text
CONFIG_CGROUP_SCHED=y
CONFIG_FAIR_GROUP_SCHED=y
CONFIG_CFS_BANDWIDTH=y
```

这是编译内核的调度器能力，不能通过 Magisk 挂载一个文件或加载普通模块补上。
使用与原构建匹配的 LLVM/toolchain、模块和 boot 打包参数；执行 KMI/ABI 对比，
检查 vendor 模块版本、符号与加载兼容性。不能直接替换为任意同版本 GKI。

## 栖序执行端所需改动

- 定义 `cpuQuotaUs` 和 `cpuPeriodUs`，0 配额表示关闭。界面同时显示“每周期可用毫秒 / 周期毫秒”。
- 例：每 100 ms 允许 50 ms，是所有组内线程合计半个 CPU 核的时间，而非整颗 SoC 的 50%。
- 用 UID 级 cgroup 归并多进程、隔离进程和 SDK sandbox；明确共享 UID 共用额度。
- 保留 Android 原有调度分组关系。Android 会在前后台迁移任务，单次写入或仅移动主线程不足以实现持续配额。
  需要核对 task_profiles 与系统任务迁移路径，再决定对应子组或 ROM 侧集成，不能在现有 controller 中盲目搬移全部任务。
- 前台、音频、导航及关键服务沿用临时豁免；停止执行、进程重用、服务崩溃和重启时恢复组与配额。
- 通过 `cpu.stat` 的 throttled 时间和多线程 CPU 时间测量验证，覆盖多核、线程新建、前后台切换、隔离进程及恢复。

## 后续实机验证边界

先备份当前 boot / init_boot（如有）及相关启动镜像并校验恢复路径。
经用户另行授权后，才考虑支持的临时启动方式或刷入；此次不执行。
必须复测系统启动、vendor 模块、音频/NFC、现有 Root 兼容和银行应用。
Android 属性可能经现有兼容配置修改，不能用 `ro.boot.vbmeta.device_state` 单独判断实际 BL 状态。

参考：[Linux CFS Bandwidth](https://docs.kernel.org/scheduler/sched-bwc.html)、
[Android stable KMI](https://source.android.com/docs/core/architecture/kernel/stable-kmi)、
[Android kernel build](https://source.android.com/docs/setup/build/building-kernels)。
