# Fuxi Design — Compose UI candidates

独立原生 Android 应用 `dev.local.fuxicontrol.design`，桌面名称 **Fuxi Design**。
不依赖 `:app`、`:core`、Vector 或 root，不声明网络/特权权限，不调用策略服务。

## Android Studio

打开根项目 `fuxi-control`，同步 Gradle，选择 `design-preview` 运行配置。
打开 `DesignActivity.kt` 的 Design/Split 视图：包含三版各四页共 12 个 `@Preview`，尺寸 412 × 915dp。
手机右上角可切换 A 原生简洁 / B 状态概览 / C 高效管理；底部“策略”打开默认模板。
应用列表点任一示例进入详情。设置提供断线、版本冲突演示。

## Build and run

```sh
./gradlew :design-preview:assembleDebug :design-preview:lintDebug
adb install --no-incremental --no-streaming -r design-preview/build/outputs/apk/debug/design-preview-debug.apk
adb shell am start -n dev.local.fuxicontrol.design/.DesignActivity
python3 tools/capture_designs.py --serial DEVICE_SERIAL
```

可用 Intent extras 定位比较页：`--es variant A|B|C --es page 概览|应用|应用策略|默认模板`。
重新定位已启动 Activity 时使用 `am start -S`（仅重启这个独立预览应用）。

## Behavior

使用同一组固定演示数据，不读取真实应用列表。搜索、筛选、运行模式、上传限速输入、预览与保存确认、恢复继承、未保存离开确认、断线只读及版本冲突可操作。
保存只作用于当前预览页面；不持久化真实策略。CPU/内存等摘要项为展示，尚未接入策略编辑。

使用 Compose Material 3 原生按钮、导航、弹层、开关和水波纹，纯色主题，没有位图渐变。
Lucide 0.468.0 的官方 SVG 路径转换为 Android VectorDrawable；ISC 许可随源码及 APK assets 提供。

实机截图见 `../docs/compose-previews`。截图使用设备实际分辨率，不冒充 Android Studio 模拟预览。
