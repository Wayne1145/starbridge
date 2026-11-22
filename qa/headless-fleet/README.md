# 无图形专服联网验证

2026-11-22 在 Windows、JDK 17、Forge 47.4.28 上实测。两个用例都从 `forgeserver` 以 `nogui` 和 `CREATE_NO_WINDOW` 启动，只监听回环地址。JVM 类加载记录包含专服和网络类。两次启动分别扫描了 Minecraft 客户端主类和 GLFW 类，命中数为零。本机停用图形客户端的标记保持启用。

| 用例 | 实际操作 | 结果 |
| --- | --- | --- |
| 完整整合包 `recovery-d` | 在 300 格载货原船的停服副本上，由专服 JVM 内的独立 TCP 端点发起标准 Forge 连接。收到的查询交给 `NetworkHooks.onCustomPayload`。 | 已核对双方都使用逻辑 `CLIENT` 接收方向。21 个查询中的前 20 个经过原处理器，第 21 个在注册表加载时终止。服务端记录断连，进入游戏的连接为零。 |
| 完整整合包保存与重开 | 登录终止后正常保存，由另一个专服 JVM 重开并再次保存。逐次核对玩家文件和货物，再读原舰桥账本。 | 原船仍在母港，13 处货站保留 221 颗钻石和 104 件铁矿或铁锭，月球旧舱空。30 份原离线玩家记录逐个同哈希，舰桥剩余 74,868 FE，返航收据与上轮原件一致。 |
| 仅装 Forge 的网络对照 `stock-control-a` | 该专服原本允许使用原版协议。两个独立套接字以正常身份进入游戏，各收到 77 个区块，回应两次心跳并移动。断线并保存后，重启同一专服，让一个人用同一身份再次登录。 | 二者都收到区块和第一次心跳后，重叠时间为 15.25 秒。两人移动后的坐标均写入各自存档。重连者从上次保存的位置进入，再接收 77 个区块并回应心跳。 |

完整包试验使用公开构建 `a90ea7c`（Actions `44969267385`），核对了上一轮的 13 个运行文件，另装了本目录的诊断附属。它从上轮已经登陆的原船副本开始。长服验收只计算完整包中通过握手并实际工作的账号，这轮新增合格时长为零。机器当前没有为这项试验运行长期专服。

实机的调用栈到 `HandshakeHandler.handleRegistryLoading`、`NetworkEvent.Context.enqueueWork` 和 `LogicalSidedProvider.get`。Forge 的正常接收流程需要客户端工作队列才能执行注册表映射。该队列在专服发行版里没有初始化。另从锁定源码核对，后续 `ConfigSync.receiveSyncedConfig` 会询问 Minecraft 客户端实例是否运行本地游戏，再调用原模组的 `acceptSyncedConfig`。这后一项是源码结论。本次完整包的证据止于注册表处理。后续若提供独立的无图形客户端运行环境，需要分别实测匹配整合包的入服、区块、移动、心跳，以及不匹配整合包的拒绝行为。[Forge 47.4.28 源码](https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-sources.jar)

从仓库根运行以下命令。两个实例名须分别使用未使用过的名称。`check_dedicated.py` 读取 `run-wide-load-original300-d` 并复制世界到 `qa/headless-fleet/.local`。`control_connections.py` 使用单独的新世界。测试分别使用回环端口 26942、26943，停服后审核保存的数据。[核验结果与原始文件校验值](verification.json) · [握手原始结果、两人连接和启动日志摘录](evidence.zip)

```powershell
py -3.12 -B qa/headless-fleet/build_fixture.py
py -3.12 -B qa/headless-fleet/check_dedicated.py my-pack-check
py -3.12 -B qa/headless-fleet/control_connections.py my-network-control
py -3.12 -B qa/headless-fleet/audit_results.py my-pack-check my-network-control qa/headless-fleet/.local/my-evidence.json
```

早期 A 轮在服务器允许登录前就发起了连接。B 轮诊断器过早读取尚未激活的网络通道，导致该测试副本退出。C 轮端点继承了服务端逻辑线程组。D 轮在正常服务开始后使用 Forge 的客户端逻辑线程组，通道信息从收到的真实查询读取。失败与正常运行分别留在对应的本地实例中。
