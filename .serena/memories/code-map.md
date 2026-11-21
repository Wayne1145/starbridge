# 从玩家操作找到服务端实现

以下源码路径按仓库根目录的 `src/main/java/dev/starbridge/` 展开。设计和玩法取舍见 mem:start-here，测试场景和规模见 mem:verification。

玩家通过 `client/ChartScreen.java` 操作航图。`voyage/VoyageNetwork.java` 把请求交给服务器，`voyage/VoyageModule.java` 处理同类命令。`voyage/Voyages.java` 的 `routeForLevel` 确定当前世界，`chart` 汇总目的地与费用，`unlocked` 查询观测和剧情，`depart` 检查驾驶员、登舰、能源与泊位。`voyage/Route.java` 计算费用及返航储备，`voyage/VoyageLog.java` 保存个人观测与母港。

游戏电脑的 `compat/computer/ShipPeripheral.java` 和外部 MCP 的 `server/AgentHttpServer.java` 都调用 `Voyages.departRemote`，再进入 `Voyages.depart`。这两个调用方已用 Serena 查询。船主需在线并在船上。游戏内 `getChart` 和外部 `get_star_chart` 供查询，`setCourse` 和 `set_course` 请求跨维航程。`tools/mcp_bridge.py` 位于仓库根目录下。同维度航点由 `navigate` 操作，`block/ShipCoreBlockEntity.java` 管控制权与能源，`flight/AutopilotController.java` 决定飞行行为，`compat/vs/VsShipBackend.java` 接入 VS。

`assembly/AssemblyModule.java` 扫描并确认自建船，`transit/BerthPlanner.java` 选择泊位。`transit/HullTransit.java` 的 `begin` 登记船员并移动同一艘船，`reconcile` 核对船身、收费并送达船员。`transit/HullManifest.java` 比对方块和方块实体。`transit/TransitJournal.java` 保存航程、收费前的船身凭据、余额、上一次收据及到达人员。收据或船身不匹配时保留待核实的航程。磁盘恢复的回归用例是 `src/test/java/dev/starbridge/transit/TransitJournalTest.java`。

`compat/unbound/UnboundCompat.java` 接入观测与个人登陆记录。原生首次登月另有玩家科技前置，测试场景预置科技只用于验收。`voyage/CabinPressure.java` 管密封、氧气和失压，`compat/unbound/CabinAirBridge.java` 将气体效果提供给 UnBound。`campaign/CampaignModule.java` 管星图和抵达事件，`campaign/SampleTube.java` 负责采集，`campaign/CampaignSites.java` 放置行动目标。`campaign/CampaignData.java` 持久化远征进度，`campaign/RelayTerminal.java` 交付物资与发放委托奖励。救援舱绑定委托编号。

`testing/DedicatedVoyageSmoke.java` 配合 `testing/VoyageCrewClient.java` 验证两个真实客户端的整船往返。`testing/DedicatedFlightSmoke.java` 验证实际受力与航点。`compat/unbound/UnboundGameTests.java` 和 `campaign/CampaignGameTests.java` 运行游戏内行为检查。
