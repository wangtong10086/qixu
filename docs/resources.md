# 资源统计与进程策略

栖序 0.6 增加整机、应用和进程三级资源视图。概览显示整机指标，应用列表支持资源排序；点击应用进入资源、进程与策略入口。资源总览提供批量设置和趋势查询。

## 统计口径

- CPU：`/proc/stat` 与 `/proc/PID/stat` 的区间差分，整机所有 CPU 合计为 100%。首次采样和计数器重置显示暂无数据。短于采样间隔的进程可能没有 CPU／IO 样本。
- RSS：进程驻留页，多个进程共享页会重复计数；应用 RSS 为成员合计，不能与整机已用内存直接相加。
- PSS：详情页按需读取 `smaps_rollup`，最多每十秒更新；共享页按映射比例分摊。
- cgroup 计费内存：内存限制作用的实际账本，与 RSS／PSS 不同。
- 网络：eBPF cgroup ingress／egress 字节，包含 IP 层开销。通过栖序预算检查的字节与栖序丢弃的字节分开；后续 Android 检查、传输失败及重传不等于应用有效载荷。
- 整机网络：外部接口计数，虚拟接口单列；不把 VPN 逻辑流量和外层流量相加。应用通过系统下载器或代理代发的数据归实际 socket 所属进程，不猜测业务调用方。
- 磁盘：进程 `read_bytes`／`write_bytes` 与整机实际块设备扇区计数，不等于普通 read/write 调用的字节数。

内核 socket cgroup ID 与当前进程 cgroup 关联。共享 socket 归创建时的 cgroup；无法对应到当前进程的流量仍保留 UID 汇总，不承诺逐进程求和总是等于应用流量。共享 UID 的网络预算本来就是共享的。进程身份包含 boot ID、PID 和启动时间，不能仅使用 PID。

当前 arm64 Linux 5.15 的 kernfs inode 是完整 64 位 cgroup ID。此设备关闭 `CONFIG_FHANDLE`，因此通过 `stat` 获取，不依赖 `name_to_handle_at`。参见 [内核实现](https://github.com/torvalds/linux/blob/v5.15/include/linux/kernfs.h)。仅当前 Android 16／arm64 平台已进入验收，不宣称所有 ROM 兼容。

## 前后台规则

应用配置新增 `processes`，以完整进程名为键。每条规则有 `foreground`、`background` 和 `exemptActiveTasks`。阶段按应用所属 UID 的前台状态决定；各字段省略表示继承。默认音乐、导航、下载豁免 CPU／内存，可逐进程关闭。关键服务保护仍优先。

CPU 调度值默认后台继承应用、前台恢复系统管理。进程前台和后台均可设置不同值。nice 为 0–19，uclamp 为 1–1024；uclamp 不是 CPU 使用率百分比，也不是时间配额。独立的 `cpuQuota` 才是时间预算。已在配套内核验证 v1 UID 聚合配额；v1 进程子配额尚未实现，隔离 UID 布局不满足条件时显示待验证。详见 [CPU 配额](cpu-quota.md)。

进程内存、网络值为额外预算，0 表示不增加进程限额。应用 UID 总预算不会复制成每个进程一份，也不能通过进程无限制绕过。内存回收阈值不是硬上限；降低 `memory.max` 可能触发进程 OOM。

新进程由系统事件触发校准，并每五秒补充校准新线程与规则。首次绑定前沿用应用 UID 预算；不是对进程第一包或第一条指令的同步拦截。改名后的进程不自动猜测匹配原规则。进程退出、方案切换、恢复继承后清理额外限制；恢复值只覆盖仍由本工具管理的状态。

## 历史与权限

资源页面可见时每两秒刷新；后台每分钟采样，不使用唤醒锁。最近 24 小时保留分钟加权平均样本，更早数据聚合为 15 分钟平均，最多七天。数据库本体上限 256 MiB，空间压力时提前移除最旧记录。重启、休眠和采样失败不是零占用，缺口不插值成测量值。

历史和采集开关独立于配置方案，通过本地 root 服务存储；无需互联网。CLI 仍要求 root，界面仍使用已有签名认证。schema 1/2 配置及所有方案先备份，再迁为 schema 3，原应用规则保留。退回旧程序时必须恢复安装前对应 schema 的备份，不能让旧程序读取新结构。

## CLI

```sh
fuxictl resources snapshot
fuxictl resources history system ''
fuxictl resources history app com.example.app
fuxictl resources history process 'com.example.app/com.example.app:worker'
fuxictl resources settings false
fuxictl resources clear
fuxictl process list com.example.app
fuxictl process apply com.example.app 'com.example.app:worker' process-rule.json
fuxictl process inherit com.example.app 'com.example.app:worker'
fuxictl apps batch-apply packages.json resource-patch.json
```

进程规则文件示例：

```json
{
  "foreground": {"cpuNice": 0, "uclampMax": 1024, "uploadBps": 1000000},
  "background": {"cpuNice": 10, "uclampMax": 512, "uploadBps": 256000, "memoryHighMiB": 128},
  "exemptActiveTasks": true
}
```

批量文件分别为包名数组与资源字段对象，只修改给出的字段。写命令支持 `--dry-run`，版本约束通过 RPC 的 `expectedRevision` 提供。界面保存使用编辑时版本，冲突后必须重新加载。

## 验证

`tools/test_process_resources.py` 仅修改临时 probe，校验 CPU 前后台值、内存恢复、PSS、独立进程 TCP 上下行以及历史查询，并在结束时恢复原 probe 配置。`tools/test_network.py` 保留 IPv4／IPv6 UDP 应用预算回归。实机结果以 [验收记录](validation.md) 为准；七天完整自然运行与业务应用行为不能由短时合成测试替代。

## 主页环形视图（0.6.0-alpha6）

主页并排展示 CPU 单环和内存／Swap 嵌套环，通过同色圆点图例对应指标。CPU 是整机 0–100% 使用率；内存已用比例按 `(MemTotal - MemAvailable) / MemTotal` 计算，Swap 单独按 `(SwapTotal - SwapFree) / SwapTotal` 计算，不与物理内存相加。容量显示已用／总量，网速单独显示上传和下载速率。

资源快照补充 `system.swapTotalBytes`，历史分钟采样及十五分钟汇总也保留该字段。旧记录缺失总量时显示未知；总量为零表示未启用。主页保留每两秒前台请求，样本超过五秒或服务断开时清空实时数值。字体放大到 1.5 倍及以上、或资源区域不足 320dp 宽时，图表纵向排列。数值过渡遵循系统动画设置，点击图表可打开既有资源详情。
