# 栖序 · Qixu

[![Android CI](https://github.com/wangtong10086/qixu/actions/workflows/android.yml/badge.svg)](https://github.com/wangtong10086/qixu/actions/workflows/android.yml)

面向 **Android 16、Magisk 与 Vector API 102** 的开源应用策略管理器。通过原生 Compose 界面和 root CLI，管理自启动、后台运行、CPU 调度、内存、逐 UID 网络限速与权限。

<img src="docs/qixu/icon.svg" alt="栖序图标" width="96" />

当前为 **0.7.0-alpha7 实验版本**，主要验证平台为 Xiaomi 13 / Android 16 / Linux 5.15。内核、ROM 私有接口和 Vector 版本会影响兼容性，不承诺其他设备直接可用。项目不提供 Root 隐藏或 Play Integrity 绕过功能。

> 当前版本包含整机／应用／进程资源视图、环形概览、Monet 配色、一键清理及 Binder 控制通道。CPU 时间配额需要匹配内核支持，详见 [资源管理](docs/resources.md) 与 [CPU 配额](docs/cpu-quota.md)。

## 能力

- 普通应用继承可修改的默认模板；逐应用仅存例外，系统角色与关键服务有保护基线。
- 后台自启动、广播、服务、作业与指定 WakeLock 管理；活动任务、音乐等运行状态参与豁免。
- 息屏延时清理缓存进程、逐应用显式强停；保护关键服务、常驻、前台任务及音频。
- 后台服务列表、组件停用与原状态恢复；新功能默认关闭。
- 签名及入口结构校验的跨版本开屏广告适配，支持逐应用开关、异常暂停与重试，验收边界见文档。
- 应用、进程和线程核心选择，名称／通配符规则、可选后台小核上限、实际核心状态与可恢复的亲和性。
- nice / uclamp、按 UID 聚合的前后台 CPU 时间配额、cgroup 内存回收阈值与硬上限；支持恢复原设置。
- eBPF 逐 UID 上传／下载限速与超限丢包统计，保留原 Android 网络检查路径。
- 权限和 AppOps 模板，初始没有拒绝清单；类型化自动化、推送代理。
- Material 3 Monet 动态深浅色界面、命名配置方案、版本冲突检查、影响摘要与恢复继承；自动化与推送使用表单，诊断和网络统计使用可视化列表。
- 名称、包名、权限与策略关键词搜索，区分声明和已授予权限；应用列表与详情显示真实图标。
- 真实进程快照可视化，区分前台、可见、后台和缓存；可见页面每 5 秒刷新。
- 本机 root 与 adb root CLI 完整控制；管理界面按 UID 和签名认证，通过本机 Binder/AIDL 与匿名管道通信。
- 首页、桌面快捷方式和快捷设置磁贴一键清理；支持单独强停应用或结束进程，保留规则预览、活动任务确认与关键服务保护。

限速默认关闭；硬内存限制可能终止应用。没有未经测量的省电比例，也不把启动成功当成所有业务功能正常。

## 开始使用

1. 按 [构建指南](docs/build.md) 安装工具链并生成 APK、原生库与服务工具。
2. 按 [安装与恢复](docs/install.md) 安装服务；首次安装保持观察模式。
3. 在 Vector 中启用模块的 Android 系统作用域，重启并检查能力，再选择执行模式。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
python3 tools/build_api.py
python3 tools/build_native.py
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

仅查看 UI 候选设计可单独构建 `:design-preview:assembleDebug`，不需要 root、Vector 或原生构建。

```sh
adb shell su -c '/data/adb/fuxicontrol/fuxictl status'
adb shell su -c '/data/adb/fuxicontrol/fuxictl explain com.example.app'
adb shell su -c '/data/adb/fuxicontrol/fuxictl search CAMERA granted'
adb shell su -c '/data/adb/fuxicontrol/fuxictl running'
```

兼容现有安装：包名仍为 `dev.local.fuxicontrol`，CLI 为 `fuxictl`，设备数据目录为 `/data/adb/fuxicontrol`。源码和构建流程公开；不提供任何私有签名密钥。自行构建与已安装版本的签名不同，不能直接覆盖升级。

## 文档与目录

[后台清理与快捷入口](docs/manual-cleanup.md)：一键强停、应用／进程结束及 CLI。

| 入口 | 内容 |
| --- | --- |
| [架构](docs/architecture.md) | 模块、数据流与权限边界 |
| [CLI 手册](docs/cli.md) / [高级核心调度](docs/scheduling.md) | 单应用文件配置、线程规则、预览、继承与恢复 |
| [策略](docs/policy.md) / [控制协议](docs/protocol.md) | 字段、继承、Binder/AIDL 与 CLI |
| [开屏广告](docs/splash-ads.md) / [后台管理](docs/background-management.md) | 适配范围、息屏策略、组件操作与验收边界 |
| [示例](examples/README.md) | 合成配置与命令，不包含个人策略 |
| [安装](docs/install.md) / [迁移](docs/migration.md) | 观察模式、升级、恢复与受限 Thanox 导入 |
| [验证记录](docs/validation.md) / [UI](docs/qixu/README.md) | 已测平台、验证边界和界面验收 |
| [贡献](CONTRIBUTING.md) / [安全](SECURITY.md) | 开发、测试和私密漏洞报告 |

`app/`：界面、系统钩子、root 服务与 CLI；`core/`：策略与自动化；`native/`：JNI、eBPF 与网络工具；`probe/`：可丢弃的验证应用；`design-preview/`：合成数据 UI 原型；`tools/`：构建、安装与显式实机测试。

## 开源许可

项目自有代码默认 **Apache-2.0**，详见 [LICENSE](LICENSE)。`native/network.bpf.c` 为 **GPL-2.0-only**；共享头文件为 **Apache-2.0 OR GPL-2.0-only**。Vector API、Lucide、Gradle 等第三方来源和许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本项目独立开发，与 Thanox、Google、Xiaomi 或 Vector 上游不存在官方关联。支持范围与迁移限制以本仓库文档为准。

资源升级历史与回滚边界：[CPU 配额、一键清理与 Binder 迁移](docs/offline-resource-upgrade.md)。最新实机结果见 [验证记录](docs/validation.md)。

### 动态配色

界面默认跟随系统壁纸色板和深浅色模式，无需单独选择主题。若系统“壁纸与样式”使用固定基础色，应用会遵循该系统色板；选择壁纸颜色后即可随壁纸取色。配色通过系统资源更新，不读取壁纸图片、不访问网络。
