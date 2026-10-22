# CLI 与单应用配置手册

本文对应栖序 0.7、策略 schema 4。所有命令均通过本机 Binder 到达同一个 root 服务；GUI 和 CLI 修改同一份配置，无网络连接依赖。APK、服务及原生库必须配套安装。

## 调用方式

手机 root shell：

```sh
su
QIXU=/data/adb/fuxicontrol/fuxictl
"$QIXU" status
"$QIXU" help
```

电脑 ADB：

```sh
adb shell su -c '/data/adb/fuxicontrol/fuxictl status'
adb shell su -c '/data/adb/fuxicontrol/fuxictl cpu topology'
```

以下简写为 `fuxictl`，不是承诺可执行文件已在 PATH 中；使用完整路径或上述变量。JSON 文件路径是**手机路径**，不是电脑路径。

## 给一个应用配置

先找到包名、现有策略和进程：

```sh
fuxictl search '应用名称' name
fuxictl app show com.example.app
fuxictl process list com.example.app
fuxictl thread list com.example.app com.example.app:worker
```

将当前例外导出，再复制到电脑编辑：

```sh
adb shell su -c '/data/adb/fuxicontrol/fuxictl app export com.example.app /data/local/tmp/app-before.json'
adb pull /data/local/tmp/app-before.json ./app-before.json
adb push ./app-policy.json /data/local/tmp/app-policy.json
adb shell su -c '/data/adb/fuxicontrol/fuxictl app apply com.example.app /data/local/tmp/app-policy.json --dry-run'
adb shell su -c '/data/adb/fuxicontrol/fuxictl app apply com.example.app /data/local/tmp/app-policy.json'
adb shell su -c '/data/adb/fuxicontrol/fuxictl cpu status com.example.app'
```

`app-policy.json` 直接是应用例外对象，不加 `apps`、包名或 RPC 外壳。可从 [示例](../examples/app-affinity.json) 开始。

```json
{
  "cpuAffinity": {"foreground": "system", "background": "little"},
  "cpuQuota": {"periodUs": 100000, "foregroundUs": 0, "backgroundUs": 20000},
  "processes": {
    "com.example.app": {
      "threads": [
        {
          "id": "render",
          "match": {"type": "exact", "name": "RenderThread"},
          "foreground": {"cpuAffinity": "big"}
        }
      ]
    }
  }
}
```

这里的 20000/100000 是每周期合计 20ms，即单核 20% CPU 时间，**不是整机八核总量的 20%**。示例用于解释格式，不是对所有应用的推荐限制。

## 合并、替换与继承

| 操作 | 行为 |
| --- | --- |
| `app apply PACKAGE FILE` | 递归合并对象；省略字段不动；数组整体替换 |
| `app apply PACKAGE FILE --replace` | 替换该应用全部例外，缺少字段恢复默认；其他应用不变 |
| `app unset PACKAGE FIELD` | 删除一个顶层字段，例如 `cpuAffinity` 或 `permissions` |
| `app inherit PACKAGE` | 删除该应用全部例外，包括其他资源规则 |
| `process apply PACKAGE PROCESS FILE` | 替换该完整进程的规则，包含它的线程规则 |
| `process inherit PACKAGE PROCESS` | 删除该进程全部额外规则 |
| `thread apply PACKAGE PROCESS FILE` | 按线程规则 ID 替换，或追加新 ID；原位置保留 |
| `thread remove PACKAGE PROCESS ID` | 删除一个线程规则 |

`threads` 是有顺序的数组：通过 `app apply` 提供它时会替换整个数组；添加单条用 `thread apply`。`permissions`、`processes` 等对象按键合并。`null` 不表示删除。删除深层字段可导出相应进程／应用文件，编辑后执行对应的完整替换。

恢复备份：

```sh
fuxictl app apply com.example.app /data/local/tmp/app-before.json --replace --dry-run
fuxictl app apply com.example.app /data/local/tmp/app-before.json --replace
fuxictl cpu status com.example.app
```

恢复继承依然受全局后台小核上限约束；它不等于禁用整个调度功能。

## 文件与命令索引

```sh
fuxictl cpu topology
fuxictl cpu status com.example.app
fuxictl cpu background-little true --dry-run
fuxictl cpu background-little true
fuxictl cpu background-little false

fuxictl process export com.example.app com.example.app:worker /data/local/tmp/worker.json
fuxictl process apply com.example.app com.example.app:worker /data/local/tmp/worker.json
fuxictl thread list com.example.app com.example.app:worker
fuxictl thread apply com.example.app com.example.app:worker /data/local/tmp/thread.json
fuxictl thread remove com.example.app com.example.app:worker render

fuxictl defaults show
fuxictl defaults apply /data/local/tmp/defaults.json --dry-run
fuxictl app set com.example.app cpuNice 10
fuxictl app set com.example.app cpuAffinity '{"foreground":"system","background":"little"}'
fuxictl limit set com.example.app --upload 1mbps --download 4mbps
fuxictl profile list
fuxictl profile create '游戏'
fuxictl profile activate PROFILE_ID
fuxictl config export /data/local/tmp/full-backup.json
fuxictl config import /data/local/tmp/full-backup.json --dry-run
```

- `profile create` 复制当前配置并立即切换到副本；选择 ID 而非名称执行 activate。调度上限也跟随方案。
- `config import` 是完整配置恢复，会替换默认模板、全部应用规则及方案；不要把单应用文件传给它。
- 导出文件使用创建模式，不覆盖已有文件，换文件名保存下一次备份。
- 对象文件可使用 `-` 从 stdin 读取，最多 256 KiB；例如 `cat app-policy.json | fuxictl app apply com.example.app -`。数组文件命令仍使用文件路径。
- 所有输出是 JSON，错误写到 stderr 且退出码非零。成功返回保存后的配置；运行执行状态另外查询。

## 预览与并发编辑

配置写命令支持 `--dry-run` 和 `--expected-revision N`。预览执行完整校验，返回 `changes` 中的 JSON Pointer 路径与前后值，不写配置、不改核心。

```sh
fuxictl app show com.example.app
# 假设上一步 revision 为 42：
fuxictl app apply com.example.app /data/local/tmp/app-policy.json --dry-run --expected-revision 42
fuxictl app apply com.example.app /data/local/tmp/app-policy.json --expected-revision 42
```

未给版本时，普通 CLI 写命令会先读当前版本，再带版本提交，防止读取和提交之间的竞争；这不能识别你几小时前导出的文件是否过期。长期编辑建议记下导出时 `app show` 的版本，提交时显式指定。冲突必须重新读取、合并和预览；不会自动覆盖新配置。

配置保存与内核执行分开。错误后先查询 `app show`、`cpu status`、`status.lastError`，不要假定错误意味着配置未保存。直接 `rpc` 保留既有请求语义，复杂客户端应自行提供 `expectedRevision`。

## 字段和单位

完整字段见 [策略表](policy.md) 与 [控制协议](protocol.md)。常用值：

| 字段 | 单位／意义 |
| --- | --- |
| `cpuAffinity` | 前后台核心选择；详见 [调度指南](scheduling.md) |
| `cpuQuota` | CPU 时间与周期均为微秒；阶段额度 0 表示关闭 |
| `cpuNice` | 0–19；优先级，不是占用百分比 |
| `uclampMax` | 1–1024；利用率提示上限，不是时间配额 |
| `memoryHighMiB` / `memoryMaxMiB` | MiB；0 不增加限制；硬上限可能触发应用 OOM |
| `uploadBps` / `downloadBps` | JSON 中是十进制 bit/s；0 不限速 |
| `burstBytes` | 字节，限速允许的突发量 |
| `permissions` | Android 权限名到 deny/system 的对象 |
| `appOps` | 操作名到 allow/ignore/deny/foreground/default 的对象 |

更多命令：应用搜索、服务管理、资源历史、清理、广告规则和诊断见 `fuxictl help` 及 [完整 RPC 表](protocol.md)。`event` 是内部系统钩子接口，不用于伪造生命周期。
