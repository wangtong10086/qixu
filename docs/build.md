# 构建

## 工具链

完整构建支持 Linux x86_64；WSL 可复用 Windows NDK。当前目标仅 `arm64-v8a`。

| 工具 | 版本 / 要求 |
| --- | --- |
| JDK | 17 |
| Android SDK | Platform 36，Build Tools 35.0.0 |
| Android NDK | 29.0.14206865 |
| Gradle / AGP / Kotlin | Wrapper 8.13 / 8.11.1 / 2.2.10 |
| Python | 3.10+，构建脚本只用标准库 |
| BPF clang | 含 BPF 后端的 clang；本机已验证 21，CI 使用 Ubuntu clang-18 |
| 其他 | Linux UAPI headers、C 编译器；安装需 adb / apksigner / keytool |

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export BPF_CLANG=clang-21
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"

sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'ndk;29.0.14206865'
python3 tools/build_api.py
python3 tools/build_native.py
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :probe:assembleDebug
```

`build_api.py` 也可从未提交的 `local.properties` 读取 `sdk.dir`。AndroidX annotation 下载会校验固定 SHA-256；wrapper 校验 Gradle 下载。首次构建需要访问 Google Maven、Maven Central 和 Gradle 分发站点。

原生构建输出 JNI 库、`app/build/networkctl` 和 `app/src/main/assets/network.bpf.o`，均忽略入库。**单独执行 Gradle 不会生成这些原生文件**；首次完整构建须先运行两条 Python 命令。BPF headers 当前按 Linux x86_64 路径处理，其他主机架构未验证。

WSL 使用 Windows NDK 时，将 `ANDROID_NDK_HOME` 指向挂载后的 SDK/NDK 目录；脚本通过 `wslpath` 调用 Windows clang，BPF 仍由 WSL clang 构建。原生 Windows/macOS 完整构建未验证。

## Android Studio

打开仓库根目录，配置 JDK 17 和 SDK 36，先运行上述 API/原生构建，再同步 Gradle。
`app` 为真实管理器；`design-preview` 为不接触设备策略的原型；`probe` 为测试应用。

## 签名与产物

```sh
./gradlew :app:assembleRelease
python3 tools/sign_release.py
```

首次运行签名工具会在 `private/signing/` 生成本机密钥和密码文件；后续复用。输出为 `private/releases/fuxicontrol-release.apk`。独立备份自己的签名；不要提交密钥或密码。CI 不使用维护者签名，也不会自动发布 APK。

本地源码构建不保证与维护者 APK 字节一致：签名、SDK 和工具链环境均参与产物生成。
