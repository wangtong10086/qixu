# 息屏清理与后台服务

新增策略默认关闭。原配置无需迁移；没有指定的字段分别按 `off`、60 秒及空组件规则处理。本地 root CLI 和签名认证界面使用同一套策略与冲突检查，不依赖网络。

## 息屏清理

应用策略和默认模板中可设置：

| 字段 | 可选值 | 含义 |
|---|---|---|
| `screenOffCleanup` | `off` / `cached` / `force-stop` | 不清理 / 请求系统清理缓存进程 / 强制停止 |
| `screenOffDelaySeconds` | 10–86400 | 息屏后的等待秒数，默认 60 |

默认模板只允许关闭或缓存清理。强制停止只能逐应用显式设置，会中断任务并可能影响后续通知。

息屏时为当时正在运行且开启清理的应用安排一次计时。亮屏取消计时；策略修改取消旧计时，按新配置重新计时。息屏后才启动的应用不在这一轮清理范围内。执行前重新核对屏幕、应用是否仍运行及保护状态。

以下 UID 不清理：系统及关键角色、显式保护、常驻、前台、活动任务、具有前台服务或正在播放音频。同一 UID 的其他包继承保护。前台服务豁免涵盖正常使用前台服务的音乐、导航及下载；不保证识别未声明前台任务的自定义下载器，可对其设置常驻或关闭清理。

`cached` 调用系统 `am kill`，由系统决定是否可以结束缓存进程；审计记录 `screen-cleanup-requested` 表示已发出请求，不等于所有进程均已结束。`screen-cleanup-skipped` 记录保护原因。

```sh
fuxictl app set com.example.app screenOffCleanup cached
fuxictl app set com.example.app screenOffDelaySeconds 60
fuxictl app set com.example.app screenOffCleanup off
```

系统钩子增加 `screen-state` 就绪检查。首次升级需要重启；缺少该钩子时不能启用息屏清理。控制服务重启后通过系统钩子的屏幕快照恢复状态。

## 后台服务

在应用策略的“后台服务”中查看声明组件、进程名、运行 PID、前台状态和客户端数量，可按组件或进程搜索。

- **停用组件**：阻止后续组件启动，使用 Android 组件状态，不强杀正在运行的进程。
- **恢复系统设置**：撤销栖序规则；恢复栖序修改前的组件状态。
- **停止服务**：尝试停止当前服务。绑定关系等情况可能使服务继续存在，界面反馈系统返回结果。

关键应用不允许新增组件停用；已存在规则在应用成为关键角色后暂停执行。停止服务时还会拒绝操作前台、活动任务、音频、前台服务及其他受保护 UID。

组件原状态写入持久日志后才修改；移除规则、切换观察/停用模式或执行恢复时撤销栖序自己的修改。原本已经停用且未经栖序修改的组件维持原状态。应用更新移除的组件保留为“已不存在”，用户可以删除对应规则。

```sh
fuxictl services com.example.app
fuxictl service block com.example.app com.example.app.SyncService
fuxictl service restore com.example.app com.example.app.SyncService
fuxictl service stop com.example.app com.example.app.SyncService --dry-run
```

组件规则只允许逐应用设置：`services: {"com.example.app.SyncService": "block"}`。可视化操作使用读取时的配置版本进行冲突校验；冲突后重新加载，不覆盖新配置。

## 验证边界

0.5.0-alpha3：构建、50 项单元测试及 lint 通过。实机已验证组件列表、预演不修改状态、组件停用、恢复原始默认状态、保留外部停用状态；测试后还原原有应用策略。

重启后 17 项系统钩子就绪。临时测试应用实机验证：前台 dataSync 服务在强停策略下被豁免、亮屏取消计时、显式强停结束普通后台服务、缓存清理请求后进程实际退出，测试结束恢复策略。停止服务实机覆盖：未运行返回 false、前台服务拒绝停止、普通后台服务停止成功、重复停止返回 false。root 守护进程直接以无应用线程的 Binder 调用停止服务，避免 Context 将其误识别为普通应用进程。真实音乐和导航任务未在本轮强制熄屏测试，不能把合成前台服务测试写作全部业务验收。

测试脚本：`tools/test_background_services.py`、`tools/test_screen_cleanup.py`、`tools/test_cached_cleanup.py`、`tools/test_service_stop.py`。息屏测试要求手机空闲，结束后可能需要手动解锁。锁屏页有独立的 10 秒熄屏限制，因此亮屏取消对照在该窗口内核验，并区分后续自动熄屏产生的新计时。

页面实机验收通过：搜索并进入临时应用、后台服务列表显示三个组件及运行数量、应用图标、返回应用策略页。自动验收脚本为 `tools/test_background_ui.py`。

最终手机安装 `0.5.0-alpha3`（versionCode 9），17 项钩子就绪，服务无错误。测试 APK 已卸载；测试前后应用策略和默认模板一致，组件恢复日志为空，没有给日常应用启用清理。

后续情景模式、跨应用询问和统计扩展尚未完成。
