# 本地管理服务与断连排查

当前版本通过 Android Binder/AIDL 连接本机 root 服务，独立 Direct Boot ContentProvider 提供接口发现，
不使用 TCP、DNS、互联网或远程服务器。管理应用也没有申请 INTERNET 权限。
VPN、Wi-Fi 和移动数据不是管理连接的前提。

## 0.4.1 修复

实机故障：重启后 daemon PID 已失效，socket 无监听者；启动堆栈显示
`ResourceController` 无法解析 `resources.json`。该文件包含大量零字节，
而 `policy.json` 与上一份策略备份正常。损坏文件已备份隔离；没有恢复旧策略。
临时资源限制属于上一次开机，重启后原进程和 cgroup 已重建。

此前原子重命名没有 fsync，不能保证重启前数据已经持久化。现在先同步临时文件，
原子替换后再同步目录。资源恢复记录附带内核 boot ID，跨开机不沿用旧记录。
同一次开机重启服务仍保留恢复记录；已有有效旧格式升级时也保留，避免遗失恢复值。
不能读取的状态仍报错，不会静默替换为默认策略。

`supervise.sh` 使用排他锁保证单个守护者，等待 daemon 退出后延迟 5 秒重启。
`stop.sh` 先设置显式停止标记，再停止守护者和 daemon，避免安装/恢复时被重新拉起。
`start.sh` 清除停止标记并等待 Binder 注册就绪。开机入口仍是 Magisk service.d，
仅等待 Android 启动完成，不检查任何网络状态。

## 查看状态

本机 root 或 adb root 执行：

```sh
/data/adb/fuxicontrol/fuxictl rpc '{"command":"status"}'
cat /data/adb/fuxicontrol/service.pid
cat /data/adb/fuxicontrol/supervisor.pid
cat /data/adb/fuxicontrol/supervisor.log
```

`service.log` 和 `service.previous.log` 保存当前及前次标准输出，Java 异常也应从
logcat 的对应 PID 查询。避免直接分享含应用策略、账号信息或完整配置的日志。
服务启动失败时应先检查异常和保留状态，不应以清空策略作为修复步骤。

## 实机验收

0.4.1 已验证：显式停止后保持停止；Wi-Fi 与移动数据关闭时可读取本地配置；
强制结束 daemon 后自动换 PID 恢复；还原网络开关后策略内容不变。
手机重启后资源记录携带新的 boot ID，服务自动启动；解锁后的界面显示
“正在执行 / 已连接 · 日常”，16/16 钩子就绪，服务错误字段为空。

## Binder 实机验收

alpha4 已验证首次解锁前连接、17 项系统钩子、发现进程退出后的重新连接，以及清理预览和返回导航。旧控制 socket 已移除。0.4.1 的断网测试属于旧传输版本，新版本未重复切换无线网络验收；不能混用两代结果。详见 [验证记录](validation.md)；迁移与回滚要求见 [离线升级说明](offline-resource-upgrade.md)。
