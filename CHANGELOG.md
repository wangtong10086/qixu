# Changelog

## 0.7.0-alpha7

- 开屏适配新增闲鱼 7.28.80、菜鸟 8.11.1023。
- 修复京东 16.1.50、小红书 9.52.0、哔哩哔哩 9.16.1 更新后入口检查失败：混淆名按已核对版本列出候选，京东改为在固定类内按方法结构定位。
- 修复首个拦截在启动 1 秒内发生时被就绪报告的节流吞掉，导致命中显示为“已加载”。

## 0.7.0-alpha6

- 修复开屏广告钩子在应用进程启动早期以 `android` 包名查找 Binder 服务被 AMS 拒绝，导致 0.6.0-alpha2 起全部适配应用未安装钩子；改为以进程自身包名和 ApplicationThread 获取提供器，发现失败按服务不可用处理。
- 开屏控制连接断开（根服务重启等）后按退避自动重连，不再等待下一次 Activity 恢复。
- 开屏握手在应用主线程上临时放宽 StrictMode，避免管道 poll 被判为主线程网络访问而中止钩子安装。
- 亲和性与调度恢复日志仍在修改线程前同步写入；已退出任务的清理、继承掩码的新线程记录和每轮结束时的刷新改为延迟合并落盘（最长 60 秒），重启后由身份校验丢弃过期记录。
- 息屏时周期性全量复核由 5 秒放宽到 60 秒，生命周期事件仍即时处理；单轮超过 1 秒记录警告。

## 0.7.0-alpha5

- 新增首页、桌面快捷方式与快捷设置磁贴的一键后台强制清理。
- 应用和进程列表、详情支持单项结束；普通对象无需确认，任务保护可单次确认覆盖。
- 使用 pidfd 与启动时间校验，防止 PID 重用误杀；执行通过现有 Binder 服务。
- 新增 cleanup quick/app/process CLI，保留原有预览接口和配置。

## 0.7.0-alpha4

- 生命周期事件按原顺序在全量校准的应用／阶段边界处理，不再等待普通 CLI／界面命令；前台事件先恢复切入应用。
- 默认调度的前台应用当场恢复原有绑核及新线程继承的限制，不再等全局遍历结束。
- 线程 nice／uclamp 变更按进程批量持久化恢复记录，再执行修改，减少同步写盘阻塞。

## 0.7.0-alpha3

- 清理已退出或复用线程的资源恢复记录，避免历史记录持续增长导致校准及 IPC 查询超时。
- v1 应用 CPU 配额纳入系统钩子确认归属的隔离 UID，所有进程共享同一预算，不再因隔离进程暂停整应用配额。
- 扩展配额验收，检查普通进程与隔离进程的实际 cgroup 成员关系以及前台恢复。

## 0.7.0-alpha2

- 修复 IPC 达到并发上限时未处理的 Binder 异常导致管理界面闪退；繁忙通过已有命令错误通道返回。
- 所有短请求共用客户端并发上限，服务端排队支持取消，避免多个页面与图标加载争抢会话。
- 修复 CPU 配额恢复时新线程继承旧 cgroup 导致 EBUSY 中断校准；配额先解除，保留日志至下次清理。

## 0.7.0-alpha1

- 增加应用／进程／线程核心亲和性、主线程与名称通配符规则，保留系统 cpuset 限制。
- 增加独立的后台小核上限，活动任务和受保护应用不能绕过；前台允许手动配置。
- 增加可视化核心选择、线程分页、实时允许核心与保存前匹配预检。
- 增加单应用文件导入导出、递归合并、线程规则和方案 CLI，以及完整中文配置手册。
- schema 4 自动迁移全部方案，新增上限保持关闭，保留旧配置备份；亲和性日志支持恢复。

## 0.6.0-alpha7

- 全部正式界面使用系统 Monet 动态配色，跟随壁纸色板与深浅色模式。
- 统一主页、资源详情、弹窗、启动背景和系统栏的主题。

## 0.6.0-alpha6

- 主页改为 CPU 单环与内存／Swap 嵌套环，保留实时网速和资源详情入口。
- 补充 Swap 总容量及历史采样，区分未启用、等待采样和数据过期状态。
- 环形图适配大字体与读屏，遵循系统动画设置。

## 0.6.0-alpha5

- 增加 v1 UID 聚合 CPU 配额、线程分组恢复记录及前后台切换。
- 增加关键应用显式 CPU 配额开关，保留其他服务保护。
- 实机验证后台 1% 额度和前台解除；系统任务迁移存在事件更新/定期复核窗口，隔离 UID 和 v1 进程子配额限制见 CPU 配额文档。

## 0.6.0-alpha4 · 安装验收修正

- 清理预览使用完整应用索引，修复加载图标时缺失 lastUpdateTime 导致的界面崩溃。
- 安装器保留 Vector 模块启用状态，并在升级后刷新 APK 路径，避免重启仍引用已删除的旧安装目录。
- 实机验证 Binder 连接、17 项系统钩子、资源首页、清理预览及返回；CPU 硬配额仍未获当前内核支持。

## 0.6.0-alpha3 · 实机安装修正

- 修复系统钩子在 ActivityManager 初始化前调用 ContentResolver 导致 system_server 重启的问题：系统 UID 等待启动完成，并使用 external-provider Binder 发现路径。
- 监听系统 Binder 死亡，由现有 supervisor 重建 root 进程，避免继续使用失效的 framework singleton。
- alpha2 为离线候选，存在上述启动缺陷，不应继续安装；使用 alpha4。

## 0.6.0-alpha2 · 离线候选

- 控制通道迁移到 Binder/AIDL，删除控制 socket 和对应 SELinux 类型注入。
- schema 3：前后台 CPU 时间配额、独立手动清理规则及全部配置方案迁移。
- 清理预览/执行/状态 CLI 与界面，执行前保护复核和配置冲突检查。
- 首页实时 CPU 动画与 60 秒曲线；缺失采样留空。
- CPU 内核提交锁定、配置准备和差异报告工具。当前设备内核及任务组尚未验收，没有可刷入镜像。

## 0.6.0-alpha1

- 增加整机、应用和进程资源采样、历史查询、应用资源排序及批量管理。
- 增加进程前后台 CPU／内存／网络规则，保留活动任务豁免及应用共享预算。
- eBPF 对不限速应用也统计流量，增加进程 socket cgroup 归属与双层预算。
- schema 1 备份迁移至 schema 2；CLI 与界面使用同一策略接口。
- 当前内核仍不支持 CPU 时间硬配额；统计口径及首次规则绑定延迟见 docs/resources.md。

## 0.5.0-alpha4

- Add per-application splash adapters for QQ Music, Zhihu, JD, Xiaohongshu, Amap, bilibili and Taobao, with local status reporting and reversible Vector scope changes.
- Restore Zhihu 11.11.1 compatibility and replace exact-version gating with signing-identity and complete entrypoint checks.
- Pause an installation after two eligible startup crashes or ANR exits within five minutes; preserve the policy switch and expose UI/CLI retry.
- Add screen-off cached-process cleanup with protected-task exemptions, explicit per-app force-stop, and visual background-service management with restoration journals.
- Document host tests, device fixtures and acceptance separately: Zhihu/Taobao behavior was manually accepted; automatic crash-suspension fault injection remains unverified on device.

## 0.4.1

- Flush atomic state files and parent directory metadata before reporting persistence success.
- Scope resource restoration records to the current boot; preserve the existing journal format on upgrade.
- Supervise the local root daemon and restart it after unexpected exit; explicit stop/recovery suppresses restart.
- Clarify that management uses a local Unix socket, remove the offline-network icon and refresh disconnected UI connections while resumed.

## 0.4.0

- Replace visible revision numbers with named configuration switching; create copies and retain independent edits, atomically with the active configuration.
- Add network rate switches and presets with explicit bit/s units and drop/burst semantics.
- Document the current kernel CPU bandwidth limitation and the prerequisites for enabling real time quotas without flashing a kernel.

- Replace settings JSON dumps with capability statuses, chronological event records and per-UID network counters linked to application icons.
- Replace raw configuration editing with configuration management and structured automation/push forms; retain CLI JSON import/export.
- Use the actual page ancestry for toolbar and system back gestures, including nested configuration pages and unsaved-edit confirmation.
- Keep revision-checked writes, typed rule validation and existing policy fields intact.

## 0.3.1

- Remove development, design and implementation commentary from the overview, settings and editors; retain actionable guidance and errors.

## 0.3.0

- Search application names, package IDs, declared/granted permissions and effective policy keywords from applications and policies.
- Add Chinese permission aliases and matching permission evidence; retain the distinction between permission grants and AppOps access.
- Visualize real app processes as foreground, visible, background or cached with process counts and UID activity markers.
- Refresh running snapshots every five seconds while a discovery page is resumed; preserve and label stale results on connection failure.
- Load real application icons on demand in lists, search results and application detail titles, with bounded image caching.
- Expose read-only `apps.index`, `apps.running` and `app.icon` protocol commands, plus `search` and `running` CLI commands.

## 0.2.0

- Rename the UI to 栖序 / Qixu and add adaptive and monochrome launcher icons.
- Adopt the native-simple Material 3 layout with overview, applications, policies and settings.
- Add policy forms, inheritance controls, save impact summaries and revision conflict handling.
- Preserve the existing package ID, root service, CLI and policy format.
- Publish build, install, protocol, licensing and contribution documentation, plus host CI.

## 0.1.0

- Introduce the Vector API 102 hook, authenticated root service, CLI, policy core and Compose manager.
- Add UID resource controls, BPF network limits, typed automation, push proxy and restricted legacy migration.
