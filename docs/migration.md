# 受限 Thanox 迁移

这是针对已验证配置形态的迁移器，不是任意 Thanox 版本和脚本的通用导入工具。新安装栖序不需要迁移或拥有 Thanox。

`export_thanos.py` 导出指定配置文件与规则数据库，保存带时间戳的私有备份、逐文件 SHA-256 和归档。需要手机具备相应数据布局、root 与 sqlite3；备份包含敏感配置，不能公开上传。

```sh
python3 tools/export_thanos.py
python3 tools/stage_migration.py private/baseline-YYYYMMDD-HHMMSS
```

第二步先校验并预览。最终导入使用 CLI `migration import DIRECTORY`，结果强制为观察模式；显式启用执行与停用原管理器是独立操作。

## 当前转换边界

- 普通应用采用栖序默认模板，不把旧自启动名单复制为所有应用的固定例外。
- 常驻名单转换为保护和常驻；待机、划走清理、WakeLock 名单只在对应总开关开启时转换。
- WakeLock 使用现有 `package-tag` 结构；不识别的格式拒绝导入。
- 旧 AppOps 只有全部为 allow 的已支持形态可导入；其他值要求明确转换。
- 推送代理当前转换微信的已知选项，不能据此宣称支持任意旧插件。
- `LegacyRules` 仅接受已知规则模板，校验条件、动作、延迟和优先级。未知或改变的 MVEL/shell 脚本会拒绝导入，不会执行。
- XML 禁止 DTD 和外部实体；迁移文件名与摘要必须通过校验。

完整映射以 `Migration.java` 与 `LegacyRules.java` 为准。旧设置可能有意被新默认替换，导入前务必审查预览。

## 设备接管工具

`guard_callback.sh`、`restore_thanox.sh` 及其相关脚本保留用于原验证环境的既有回调接管。它们包含环境相关路径，不属于通用首次安装流程。其他设备应先确认目录、回调和原管理器状态，不能直接照搬接管命令。

常规栖序恢复使用 [install.md](install.md) 中的 `recover.sh`，不会操作 Thanox。迁移、接管及推送的实测范围见 [validation.md](validation.md)。
