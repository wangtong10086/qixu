# CPU 时间配额

## 当前实现：0.7.0-alpha3

小米 13 的配套内核支持 CFS bandwidth。v1 后端把普通进程、RCS 子进程和系统钩子确认归属的隔离 UID 放入同一个应用预算组，按应用聚合限额，不给每个进程复制额度。前台额度为 0 表示不限制；原 CPU 分组在解除时恢复。v2 后端尚未归并独立 UID 树，仍会报告 `pending-layout`。

当前验收设备的信息与 Gmail 分别采用 100ms 周期、50ms 后台 CPU 时间，即各单核 50%（八核总量约 6.25%），前台不限额。没有叠加低 nice、低 uclamp 或内存限制。此为该设备的可修改配置，不是新安装默认值；收信延迟和同步任务仍需持续观察。

之前的 0.5% 长周期配额曾与其他限制叠加，短信出现后台任务超时；Gmail 后续出现隔离 UID 导致原后端暂停配额。下面的早期低配额验收是历史记录，不应作为当前推荐值。

## 历史实现：0.6.0-alpha5

小米 13 已刷入启用 CFS bandwidth 的配套内核。栖序支持在现有 v1 CPU controller 下创建按 UID 聚合的预算组，并保留原有 v2 执行路径。默认短信与 Gmail 目标为后台占用低于单核 1%。实测名义 1% 时短信仍达到约 1.29%，为调度误差预留余量，最终配置名义 0.5%：`periodUs=1000000`、`backgroundUs=5000`、`foregroundUs=0`、`allowProtected=true`。其他设置保留，配置可在应用详情的 CPU 时间配额中修改。

- 同 UID 的普通进程与线程共用预算；默认短信的 RCS 进程也在同一额度内。
- `allowProtected` 只允许显式配额覆盖关键应用/常驻豁免，不取消自启、联网等保护，也不取消音乐/导航/下载活动豁免。
- 线程原 CPU 组与启动标识持久记录；解除配额只迁回仍由本服务管理的线程，尊重 Android 已自行完成的迁移。服务重启复用同一启动周期的记录，设备重启丢弃旧线程身份。
- 前后台和进程事件触发更新，另每 5 秒复核。组内由内核执行硬配额；**系统重分组或新进程首次绑定存在收敛窗口，不承诺每个瞬时采样都低于 1%**。绑定时暂时使用 UID 聚合调度组，解除后恢复原 CPU 组；cpuset、内存和网络分组不迁移。
- 检测到未聚合的隔离 UID 时暂停该 UID 配额并报告 `pending-layout`，不把相同额度复制到多个组。
- 进程级 v1 子配额尚未实现；本版验收范围为按应用 UID 聚合的后台配额。

实机临时应用主进程与工作进程：12.08 秒内合计 CPU 时间 0.13 秒（10ms 计数精度），前台解除及临时组删除通过。测试应用与测试策略已清理。短信/Gmail 的运行状态均为 `applied`，最终生产配置内核读回为 `5000 / 1000000`。30.15 秒真实应用采样中，短信（含 RCS）累计 0.23 秒 CPU，约 0.76%；Gmail 累计 0.08 秒，约 0.27%。服务重启后 17 项钩子恢复、两个配置重新生效；与修改前快照对照，仅这两个应用的 cpuQuota 字段变化。该短期采样不证明所有未来负载或切换窗口都不超过 1%。

## 历史：刷入前的调查记录

以下至刷写准备各节保留当时状态；后续内核和应用执行端均已完成阶段性验收，以本文顶部当前实现及末尾刷入记录为准。

初始只读调查：Xiaomi 13 的内核为 `5.15.207-g03e6e48a5b4e`，Clang 21，
`CONFIG_CGROUP_SCHED=y`、`CONFIG_FAIR_GROUP_SCHED=y`，但
`CONFIG_CFS_BANDWIDTH` 未启用。CPU controller 挂载在 cgroup v1 的 `/dev/cpuctl`，
不存在 `cpu.cfs_quota_us` / `cpu.cfs_period_us`。本次没有修改或刷写启动分区。

因此当前版本不承诺 CPU 时间硬配额。nice 调整竞争优先级，uclamp 调整调度利用率约束，
两者都不能限制一个周期内消耗的总 CPU 时间。也不使用 SIGSTOP/SIGCONT 循环模拟：
这种方式可能暂停持锁线程，并破坏 Binder 和前台切换时序。

## 内核改动

必须找到与 `03e6e48a5b4e` 构建对应的完整源码、补丁和构建配置；版本字符串不能证明
某个公开分支与正在运行的镜像相同。已定位对应公开提交 `03e6e48a5b4ed606dfdd48bc782a57f9c778938b`，锁定信息见 `kernel/source-lock.json`。完整设备构建输入仍未验证，因而未生成可刷入镜像。

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

- 配置 `cpuQuota.periodUs`、`foregroundUs`、`backgroundUs`，0 配额表示关闭。界面同时显示“每周期可用毫秒 / 周期毫秒”。
- 例：每 100 ms 允许 50 ms，是所有组内线程合计半个 CPU 核的时间，而非整颗 SoC 的 50%。
- 用 UID 级 cgroup 归并多进程、隔离进程和 SDK sandbox；明确共享 UID 共用额度。
- 保留 Android 原有调度分组关系。Android 会在前后台迁移任务，单次写入或仅移动主线程不足以实现持续配额。
  需要核对 task_profiles 与系统任务迁移路径，再决定对应子组或 ROM 侧集成，不能在现有 controller 中盲目搬移全部任务。
- 前台使用单独额度（默认关闭），音频、导航及关键服务沿用临时豁免；停止执行、进程重用、服务崩溃和重启时恢复组与配额。
- 通过 `cpu.stat` 的 throttled 时间和多线程 CPU 时间测量验证，覆盖多核、线程新建、前后台切换、隔离进程及恢复。

## 后续实机验证边界

先备份当前 boot / init_boot（如有）及相关启动镜像并校验恢复路径。
经用户另行授权后，才考虑支持的临时启动方式或刷入；此次不执行。
必须复测系统启动、vendor 模块、音频/NFC、现有 Root 兼容和银行应用。
Android 属性可能经现有兼容配置修改，不能用 `ro.boot.vbmeta.device_state` 单独判断实际 BL 状态。

参考：[Linux CFS Bandwidth](https://docs.kernel.org/scheduler/sched-bwc.html)、
[Android stable KMI](https://source.android.com/docs/core/architecture/kernel/stable-kmi)、
[Android kernel build](https://source.android.com/docs/setup/build/building-kernels)。

## 历史：v2 候选实现

配置、前后台编辑、恢复日志及已有 v2 UID/PID 组的 cpu.max 支持已加入。当前小米 13 的 v1 分组仍需内核和任务组集成，不宣称生效。进度、准备工具及限制见 [离线升级说明](offline-resource-upgrade.md)。

## 2026-10-22 刷写前准备记录

用户已授权刷内核。当前实机仍为原内核，活动槽 B。已从手机读取并校验 boot、init_boot、vendor_boot、dtbo、vbmeta、vendor_dlkm、system_dlkm 备份，原始镜像和配置保存在工作区 private，不进入 Git。

- 源码提交与 tree 均匹配 source-lock；已提取实际 config、BTF、task_profiles 和 cgroups。
- 400 个 vendor 模块已归档。321 个记录主内核提交，78 个记录外部模块提交 f5956ffd9766，camera 模块记录同提交 dirty；不能用公开分支 HEAD 覆盖这些模块。
- 原模块编译器为 Android Clang r563880c，构建 14054515，LLVM revision 与运行内核一致。
- 原内核公开模块信任证书已从 Image 提取，未获取或更改私钥。
- 候选功能配置仅启用 CFS_BANDWIDTH；VMI 上 Full LTO 主内核构建通过，Image 和 Module.symvers 已下载并校验 SHA-256。配套外部模块与整套启动兼容性仍在验证。
- BTF 显示 task_group 的 cfs_bandwidth 为空、android_vendor_data1 位于字节 432；启用配额会改变该结构布局。sched-walt.ko 在同提交源码中直接访问该字段，因此不能只换 boot 并继续加载旧 WALT 模块。

刷写门槛：原配置对照构建、配额构建、现存模块符号与布局兼容检查、匹配 WALT 模块及启动镜像打包、恢复路径验证。栖序当前仅有 v2 cpu.max 执行端，实机 v1 仍需 UID 聚合及 Android task profile 集成；内核启动成功不等于应用时间配额已验收。

随后找回 [相机兼容仓库](https://github.com/wangtong10086/xiaomi13-camera-kernel-compat)：production-v11 的 camera.ko 与 vendor_dlkm 镜像 SHA-256 均与当前设备一致，18 个兼容文件已应用于固定的外部模块提交。相机源码来源已解决，不能再将 dirty 标记当作未知补丁。

已完成调度器局部对照构建：原配置 113 个现用调度器导出符号 CRC 与设备模块要求一致；配额配置改变 76 个调度器导出符号，其中至少 23 个现有模块引用了改变的接口，包括 camera、Wi-Fi、显示、GPU、USB、触控及 WALT。task_group 大小由 512 变为 768，android_vendor_data1 偏移由 432 变为 664；rq 大小由 4544 变为 4608。该结果禁止单独替换 boot，必须配套模块与镜像。

按用户要求转移到 46 GiB 内存的 VMI 构建，保留 Full LTO。固定源码、18 个相机兼容覆盖文件与编译缓存已校验；Clang 两次下载的压缩包字节不同，但全部 10,498 个归档条目的内容、模式和链接目标一致。设备镜像与私人数据未上传 VMI。原配置已编译对象覆盖的 3,955 个现用导出 CRC 与设备要求一致；完整配额 Module.symvers 再次确认 23 个模块的导入 ABI 改变。尚未刷写。

## 2026-10-22 VMI 构建及刷入结果

已在 VMI 完成原配置衍生的 Full LTO 内核和所需外部驱动构建，下载到本机并核验。保留原相机兼容覆盖。组装 400 个 vendor 模块，其中 344 个重编译、56 个沿用；23,114 项导入 CRC 检查通过。vendor_boot 中 327 个模块与组装清单一致，其中 321 个更新。CRC 检查不代替运行验收。

在 Fastboot 确认 fuxi、BL unlocked、活动槽 B 和分区容量后，成套刷入 boot_b、vendor_boot_b、vendor_dlkm_b；刷后读取 SHA-256 均与候选镜像一致。init_boot、dtbo、vbmeta、modem、用户数据未改动。原镜像同时保存于 WSL private 和 Windows 备份目录，含回退说明。

- 启动成功，CONFIG_CFS_BANDWIDTH=y；/dev/cpuctl 出现 cpu.cfs_quota_us、cpu.cfs_period_us、cpu.stat。
- 388 个模块加载；启动日志未发现 Unknown symbol、版本 CRC 不匹配、CFI failure、BUG 或 Call trace。
- 栖序 enforce 服务与 17 个钩子就绪；电话双卡重新注册 NR SA，相机服务列出 11 个设备，NFC 开启。
- 两个临时忙循环进程共享 20,000/100,000 微秒配额，10.07 秒墙钟时间内累计 CPU 时间 2.00 秒；101 个周期发生节流。测试进程和分组已清除，根组额度仍为 -1。

上述验证证明内核配额执行能力，不代表栖序逐应用配额已接通：当前执行端仍需增加符合 Android task profile 迁移规则的 v1 UID 聚合方案。相机实际拍摄、银行/Wallet、物理钱包手势及长时间蜂窝切换尚未在这次内核更新后验收。
