# 安装、升级与恢复

仅针对已具备 Magisk root、Vector API 102 和兼容内核接口的 Android 16 主用户。当前实测基线见 [validation.md](validation.md)。独立 APK 不能替代 root 服务、原生工具及 Vector 系统钩子。

## 首次安装

先完成 [完整构建与签名](build.md)，连接一台测试设备。脚本通过 adb 操作所选设备；多设备时设置 `ANDROID_SERIAL`。

```sh
export ANDROID_SERIAL=YOUR_DEVICE_SERIAL
python3 tools/install.py --apk private/releases/fuxicontrol-release.apk
adb shell su -c '/data/adb/fuxicontrol/fuxictl status'
```

安装工具会安装管理 APK、复制自身服务文件、记录管理签名、设置自身开机脚本并启动服务。**全新配置为 observe**；升级保留已有模式与策略。工具保留 Vector 原有启用状态，升级时刷新已启用模块的 APK 路径；不会自动启用未启用模块，也不会停用 Thanox；首次安装不要求任何 Thanox 备份。

在 Vector 管理器中启用栖序模块，作用域选择 Android 系统（system / 用户 0），然后重启。检查 `capabilities`、`status` 中的钩子与 `lastError`。确认兼容后再执行：

```sh
adb shell su -c '/data/adb/fuxicontrol/fuxictl mode enforce'
```

这一步会应用默认模板。先审查默认规则与关键服务，避免把首轮验证放在日常使用的重要应用上。观察模式和执行模式的区别见 [策略文档](policy.md)。

## 升级

完整升级同样使用 `install.py --apk ...`。已有服务会先停止，备份到本机 `private/controller-before-*.tar.gz`，再替换自己的文件并启动。若中途失败，保留错误与备份，修复原因后重新安装；不能假定失败时服务仍在运行。

使用相同签名，保留包名和数据目录。仅 UI 改动可直接 `adb install -r APK`；如服务、JNI、BPF、CLI 或钩子变化，必须使用完整安装并按变更要求重启。低版本号 APK 可能被 Android 拒绝覆盖。不要为了绕过签名检查卸载管理器或清除其数据。

安装前可导出策略（文件保存在设备上）：

```sh
adb shell su -c '/data/adb/fuxicontrol/fuxictl config export /data/local/tmp/qixu-policy.json'
```

导出含应用清单和个人规则，应私下保存，不上传 issue。

## 暂停与恢复

```sh
adb shell su -c '/data/adb/fuxicontrol/fuxictl mode observe'
adb shell su -c 'sh /data/adb/fuxicontrol/recover.sh'
```

第一条进入观察模式并撤回受管限制；第二条用于故障恢复，停止自身服务和启动脚本、关闭自身 Vector 激活并恢复受管设置。系统钩子在重启后卸载。不会停用或恢复其他模块。

若曾执行特定的 Thanox 接管，另见 [迁移文档](migration.md)。`restore_thanox.sh` 和相关回调工具是既有接管环境的恢复工具，不应在新设备上直接运行。

从旧控制 socket 版本升级至 Binder 版本时，必须同步更新管理 APK、root daemon 与钩子并重启，避免混用传输协议。schema 1/2/3 导入会备份并迁移到 schema 4，包括命名配置方案；降级应恢复安装前完整配置，不能让旧程序直接读取 schema 4。

0.7 的核心调度需要更新 APK、root 服务及 JNI。迁移不自动开启后台小核上限；临时应用验收后再启用。降级前使用新服务进入观察模式，确认亲和性已恢复，再恢复旧程序和升级前配置。
