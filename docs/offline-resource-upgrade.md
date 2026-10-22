# 资源管理与 Binder 升级：历史设计与回滚

本文保存 alpha2 离线阶段的设计和限制，后文“尚未”“当前”均指当时。后续 alpha4 已完成 Binder 基础验收，alpha5 已在配套内核验证 v1 UID CPU 配额，alpha6/7 更新环形视图与动态配色。当前状态见 [验收记录](validation.md) 与 [CPU 配额](cpu-quota.md)。alpha2 存在启动缺陷，不应安装。

## 已实现

- 控制端使用 AIDL Binder。独立 Direct Boot provider 只做发现，root daemon 执行策略。
  管理界面、CLI、系统钩子、开屏钩子的控制 UNIX socket 已移除；网络限速的测试 TCP/UDP 和 eBPF socket 归属统计保留。
- 配额使用 `cpuQuota: {periodUs, foregroundUs, backgroundUs}`，默认周期 100000 µs，两阶段默认关闭。
  配额是组内全部线程合计的 CPU 时间，不是设备总 CPU 百分比。进程配额是应用预算下的额外限制。
- CPU 执行端支持已有 cgroup v2 UID/PID 层级的 `cpu.max`，沿用按 boot ID/cgroup ID 记录的恢复日志。
  不创建或搬迁 Android 任务组。没有 CPU controller、线程不在统一组、隔离 UID 尚未合并时，应用配额显示待内核／布局验证。
  当前已知小米 13 使用 v1 CPU controller，故本轮不能宣称 CPU 硬配额在手机上生效。
- 手动清理规则独立于息屏规则。默认回收缓存、后台至少 60 秒；CPU 30 秒平均和 RSS 阈值默认不启用。
  规则为 AND。前台、前台服务、活动任务、音频、关键角色和常驻应用受保护。
  强制停止只能逐应用配置；共享 UID 不执行批量强停。
- 预览固定目标集，60 秒过期，绑定配置 revision。每项执行前重新检查保护和配置；执行模式之外只能预览。
  返回 requested/exited/skipped/failed，不猜测释放多少内存。
- 首页每 2 秒读取资源快照，显示 CPU 曲线、内存及网络速率。60 秒曲线保留缺口，页面非 resumed 停止轮询，遵循系统动画开关。
  清理预览会临时采样 45 秒，用于凑齐连续 30 秒 CPU 窗口；不持有唤醒锁。
- schema 3 升级前保存 `policy.schema1.backup.json` 或 `policy.schema2.backup.json`，递归迁移所有配置方案，保留已有例外。

## CPU 内核边界

`kernel/source-lock.json` 固定了与已记录 release 后缀一致的公开源码提交。
这只证明找到了提交，不能证明配置、编译器具体构建、vendor 外部模块与手机镜像一致。

`kernel/cpu-quota.config` 提供所需内核选项。
`tools/prepare_cpu_kernel.py` 检查干净且匹配的源码树，依据原始配置生成独立候选配置、差异及输入 SHA-256 报告。
不覆盖源码或基线，不打包或刷写启动镜像。

```sh
python3 tools/prepare_cpu_kernel.py --source /path/to/pinned/kernel \
  --baseline /path/to/verified/device-baseline --output /path/to/new-output
```

缺失项：当前内核 config、精确 Android Clang 21 构建、vendor 模块 revision/Module.symvers、当前启动镜像和 task_profiles/cgroups 配置。
已有较早 ROM 镜像不能当作当前恢复基线。也不能仅打开 CFS_BANDWIDTH 就把当前 v1 CPU 分组切换为 v2；
必须保留 Android 调度语义并验证统一 UID 预算。若最终采用 v1 UID 层级，需要补上对应后端，不能把同一额度分别复制到各调度子组。

## 安装前与安装后检查

手机恢复可用后再执行：

1. 保存当前 APK、签名摘要、服务脚本、完整 policy 和 resources 日志，核对原配置备份可恢复。
2. 原签名更新 APK，同时更新 root daemon APK 和 start 脚本；旧系统钩子需重启加载，不能混用 socket 和 Binder 两代程序。
3. 验证开机 Direct Boot 注册、UI/CLI 连接、系统事件和开屏钩子。关闭网络/VPN 仍应正常控制。
4. 杀死 broker / daemon / UI，确认死亡处理和重新发现；重复启动只允许一个 daemon。
5. 临时测试 APK 验证普通 UID 无管理权限、系统 UID 不能修改策略、广告接口不能查询其他应用；验证大响应、超时、取消与并发。
6. 仅用临时测试应用验证清理、豁免、配置冲突、CPU/内存阈值。真实应用强停不作为自动验收操作。
7. 检查首页曲线、暂停刷新、系统关闭动画、详情返回和图标。恢复测试配置，最后再验证现有银行、Wallet、代理、推送和钱包按键。

当前环境没有 Android emulator，Binder 运行时和界面交互未实测。CPU 配额须待匹配内核、分组方案和另行授权的内核测试，不随 App 安装自动刷内核。

## 回滚

停止新版 root 服务后，恢复安装前 APK、daemon APK、脚本和配置完整备份；旧程序不能读取 schema 3。
重启加载旧系统钩子。资源恢复应使用同一次开机且身份匹配的日志，跨开机不能沿用 PID/cgroup 身份。
若后续测试内核，恢复启动镜像的流程与 App 回滚分开；当前交付没有刷写命令。
