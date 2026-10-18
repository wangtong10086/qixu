# Fuxi Control

独立源码的 Android 16 应用策略管理器：root 服务、现代 Vector API 102 系统钩子、Compose 管理界面及 CLI。

**已导入现有活动策略并切换到执行模式；Thanox 模块和应用已停用，数据保留。重启后服务、管理界面、Play Integrity 三绿和 Wallet 安全要求已复测通过。**
现有银行兼容、Play Integrity、NFC 和电源键钱包模块独立保留。

## 构建

需要 JDK 17、Android SDK 36、Build Tools 35、NDK 29 和支持 BPF 的 clang。
在 `local.properties` 指定 `sdk.dir`，或设置 `ANDROID_HOME`。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_NDK_HOME=/path/to/ndk/29.0.14206865
python3 tools/build_api.py
python3 tools/build_native.py
./gradlew :core:test :app:assembleDebug :probe:assembleDebug
```

原生构建支持 Linux NDK，也支持 WSL 调用 Windows NDK。
调试签名仅用于开发；本机接管使用固定项目 release 签名，并已完成恢复验证。
私有备份、设备配置、构建产物和密钥均不进入源码仓库。

## 安装与 CLI

```sh
python3 tools/export_thanos.py
python3 tools/install.py
adb shell su -c '/data/adb/fuxicontrol/fuxictl status'
adb shell su -c '/data/adb/fuxicontrol/fuxictl help'
```

导出先备份，文件名带时间，不覆盖旧备份。安装保留现有策略与 Thanox 状态。
CLI 使用 root 权限；普通 shell 无写权限。界面需要匹配管理应用 UID 和固定签名摘要。
系统钩子连接只允许事件、状态和策略订阅。所有控制使用同一 JSON 命令协议。

```sh
fuxictl defaults show
fuxictl app show com.example.app
fuxictl app set com.example.app autostart deny
fuxictl limit set com.example.app --upload 512kbps --download 2mbps
fuxictl app inherit com.example.app
fuxictl config export /data/local/tmp/policy-backup.json
fuxictl mode observe
```

`rpc JSON` 提供完整协议入口；变更支持 `--dry-run`。配置中的速率单位为十进制 bit/s，0 表示不限速。
普通应用始终继承当前默认模板，逐应用仅保存例外。系统、默认输入法、钱包、VPN 等关键服务受保护。
隐私模板初始为空。运行时权限及 AppOps 变更记录原值，停用时恢复仍由本服务管理的设置。

## 网络限速

使用按 socket UID 分方向的 BPF 令牌桶，超限丢包，提供通过与丢弃的字节/包计数。
TCP 实际吞吐可能低于上限；允许一个配置的突发窗口，不能用短时读数判断长期上限。
限速不是 CPU 百分比配额，也不保证省电；默认关闭。

当前 ROM 在根 cgroup 中使用不可覆盖的单程序 Android 网络检查。网络控制器保留并固定原程序，
先填好尾调用表，再原子替换入口；通过限速检查的流量继续执行原有 Android 检查。
尾调用失败会丢包，不能绕过系统权限。停用只恢复当前仍指向本控制器的入口。
不清空系统防火墙、tc、netd 或其他模块的 BPF 规则。

## 验收状态

见 [docs/validation.md](docs/validation.md)。观察模式不施加应用限制。
全局执行等待系统钩子与前台快照就绪。真实微信 FCM、VPN 流量及 SDK sandbox 专项测试仍待完成。

## 回退

本机接管前已保存原回调及 Thanox 配置。恢复新服务管理的设置并重新启用 Thanox：

```sh
adb shell su -c 'sh /data/adb/fuxicontrol/restore-thanox.sh'
adb reboot
```

该脚本保留新应用和全部旧数据，停用新服务启动脚本及系统钩子。
只想暂停策略时使用 `fuxictl mode observe`，无需切换模块。
