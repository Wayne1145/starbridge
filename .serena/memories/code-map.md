# 从玩家操作找到服务端实现

以下源码路径按仓库根目录的 `src/main/java/dev/starbridge/` 展开。设计和玩法取舍见 mem:start-here，测试场景和规模见 mem:verification。

玩家通过 `client/ChartScreen.java` 操作航图。`voyage/VoyageNetwork.java` 把请求交给服务器，`voyage/VoyageModule.java` 处理同类命令。`voyage/Voyages.java` 的 `routeForLevel` 确定当前世界，`chart` 汇总目的地与费用，`unlocked` 查询观测和剧情，`depart` 检查驾驶员、登舰、能源与泊位。`voyage/Route.java` 计算费用及返航储备，`voyage/VoyageLog.java` 保存个人观测与母港。

游戏电脑的 `compat/computer/ShipPeripheral.java` 和外部 MCP 的 `server/AgentHttpServer.java` 都调用 `Voyages.departRemote`，再进入 `Voyages.depart`。这两个调用方已用 Serena 查询。船主需在线并在船上。游戏内 `getChart` 和外部 `get_star_chart` 供查询，`setCourse` 和 `set_course` 请求跨维航程。`tools/mcp_bridge.py` 位于仓库根目录下。同维度航点由 `navigate` 操作，`block/ShipCoreBlockEntity.java` 管控制权与能源，`flight/AutopilotController.java` 决定飞行行为，`compat/vs/VsShipBackend.java` 接入 VS。

`assembly/AssemblyModule.java` 扫描并确认自建船，`transit/BerthPlanner.java` 选择泊位。`transit/HullTransit.java` 的 `begin` 登记船员并移动同一艘船，`reconcile` 核对船身、收费并送达船员。`transit/HullManifest.java` 比对方块和方块实体。`transit/TransitJournal.java` 保存航程、收费前的船身凭据、余额、上一次收据及到达人员。收据或船身不匹配时保留待核实的航程。磁盘恢复的回归用例是 `src/test/java/dev/starbridge/transit/TransitJournalTest.java`。

`compat/unbound/UnboundCompat.java` 接入观测与个人登陆记录。原生首次登月另有玩家科技前置，测试场景预置科技只用于验收。`voyage/CabinPressure.java` 管密封、氧气和失压，`compat/unbound/CabinAirBridge.java` 将气体效果提供给 UnBound。`campaign/CampaignModule.java` 管星图和抵达事件，`campaign/SampleTube.java` 负责采集，`campaign/CampaignSites.java` 放置行动目标。`campaign/CampaignData.java` 持久化远征进度，`campaign/RelayTerminal.java` 交付物资与发放委托奖励。救援舱绑定委托编号。

`testing/DedicatedVoyageSmoke.java` 配合 `testing/VoyageCrewClient.java` 验证两个真实客户端的整船往返。`testing/DedicatedFlightSmoke.java` 验证实际受力与航点。`compat/unbound/UnboundGameTests.java` 和 `campaign/CampaignGameTests.java` 运行游戏内行为检查。

原包船坞入口在 `compat/rocketry/OriginalRocketApi.java`。它识别原机与原火箭，按原机按钮调用扫描或组装。`orbit/OrbitModule.java` 提供 `bindCabin` 与 `cabinBounds`。这些接口的存在不能证明已经走通造船流程。接手先看 docs/shipyard-verification.json 的实机阻塞，以及 mem:start-here 的无命令界面验收。

太空驾驶时，`client/orbit/OrbitScreen.java` 显示真实方块船体、可改绑的手动与巡航操控、速度和着陆仪，`client/orbit/HullMesh.java` 缓存船体模型。`orbit/OrbitWire.java` 传输操控和驾驶数据，`orbit/OrbitModule.java` 判定控制权、船员位置、受保护能源与进入大气的条件。`orbit/OrbitLedger.java` 持久化位置、速度和固定船舱。实测链接见 mem:verification。

`OriginalShipyard` 绑定原机交互与舱区。`ShipyardWire` 校验界面操作与距离。`RocketTransfer` 在原机实际打包前封存当时的货物，在原生发射后对照原火箭存储，展开唯一固定舱。`ShipyardHull` 保存机器和连接方块状态。`client/shipyard/ShipyardScreen.java` 展示可切层旋转的船坞界面。`OriginalShipyardFixture` 是仅隔离存档开启的原包专服回归。

首次使用航图终端时，`voyage/NavigationSlate.java` 请求打开 `client/FirstFlightScreen.java`。两页图解指向自己的舰桥和原发射台，使用键文字取自当前绑定。测试用种子补正单独位于 `qa/retrofit-lunar-seed`。

`compat/galacticraft/OriginalClientDimensions.java` 在 Forge 远程客户端为宿主公布的原行星配对现有提供器，退出时清理本次登记。对应复现见 docs/original-runtime-verification.json 的 encounter。

`compat/rocketry/CrewSeats.java` 将额外乘员绑定到各自安装的原座椅；`RocketTransfer` 在实际升空后按座位把相同人员送到固定船舱。`client/shipyard/ShipyardClientFixture.java` 驱动两套真实游戏界面，对照 `OriginalShipyardFixture` 的服务端乘员与货物断言。

正式包的旧地形种子兼容在 `compat/galacticraft/OriginalTerrainPolicy.java`、`OriginalTerrainSeeds.java` 与 `mixin/OriginalGeneratorSeedMixin.java`。`OriginalDeferredTileMixin.java` 保留跨区块时上游设置的首领方块实体。`tools/original_world_profile.py` 校验和安装双端独立下载的上游发行包。

原月球密封舱保护由 `compat/rocketry/OriginalCabinAir.java` 连接旧端原真空事件。`voyage/VentMenu.java` 负责供氧读数和补气，`client/VentScreen.java` 显示仪表。`qa/original-flight` 提供双客户端供氧、航程、船坞与地牢联飞场景。

近地驾驶控制绑定见 `client/SurfaceControls.java` 和 `client/PilotScreen.java`。通风口用 `voyage/VentMenu.java` 的原版菜单同步气量、所在位置密封和补充动作，客户端为 `client/VentScreen.java`。`qa/habitat-ui` 从无作弊单人世界进入原版局域网模式。`transit/PassageStorage.java` 保存当前船体涉及的两端区块，在存储完成后提交航费。

`compat/rocketry/CabinChunks.java` 预先加载固定舱泊位，只对原船体和新舱体实际覆盖的区块做入轨持久化。`LoadedShipyardFixture` 和 `qa/shipyard-load/run.py` 复现跨服启动的 100–300 格长原船及船尾设备检查。

`ShipyardScreen.suggestedPart` 将原机状态关联到示例中的甲板和高亮零件，原宿主世界里的实际生存摆放由普通方块交互处理。

`client/FirstFlightScreen.java` 接待未绑定终端，也可从 `client/ChartScreen.java` 返回。舰桥配方页读取本世界同步的有序配方，配方书在收到铁锭后解锁舰桥。`qa/habitat-ui/src/main/java/dev/starbridge/qa/habitat/FirstCraft.java` 在无作弊的原版工作台验证开启配方书条目、合成和放置舰桥。`tools/package_dev_profile.py` 按锁定清单生成两侧附属的安装 ZIP。

短时舰队在 `qa/connected-fleet`。`ConnectedFleet.java` 记录三条巡航、分档刻耗时和熔炉产量，`FleetPeers.java` 在一个 Forge 进程中建立独立游戏连接，`audit.py` 交叉核对服务端和客户端记录。复现见 `qa/connected-fleet/orbital-run.md`。

返航收费恢复在 `transit/HullTransit.java` 的 `paidCheckpoint`、`PassageStorage.java` 和 `TransitJournal.FareProof`。新航程按两端实际船体区块的交易印记核对，旧档用 `LegacyPaidHull.java` 对照原组装机档案。库存以已落盘的船体为准。`qa/original-flight/restart_server.py` 在两个服务进程之间保留证据，`inspect_restart.py` 读离线区块，`RestartCheck` 在两名客户端重登后核对船员、货物和原收据。
