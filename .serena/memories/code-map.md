# 从玩家操作找到服务端实现

以下源码路径按仓库根目录的 `src/main/java/dev/starbridge/` 展开。设计和玩法取舍见 mem:start-here，测试场景和规模见 mem:verification。

本机「首航安装检查」由 `client/setup/FlightInstallScreen.java` 显示。`client/setup/FlightInstall.java` 按随 JAR 打包的 `pack/original-spaceflight.lock.json` 提供文件、目录和作者来源，从当前 Forge 及 Retrofit 的活动模组与船坞转换器读取载入状态。合并版 Galacticraft 在清单中只有一份。 等待旧侧启动时可从原组件直达 Retrofit，并返回同一组件。模型回归用例在 `src/test/java/dev/starbridge/client/setup/FlightInstallTest.java`。

原版进度标签在 `src/main/resources/data/starbridge/advancements/first_flight/`。根节点、指南针和终端分别给出起步、解锁配方、使用终端的入口。配方的实际解锁仍在相邻的 `recipes/navigation_slate.json`。对应的中文客户端验收是 `qa/habitat-ui` 的 `FirstStepsCheck` 和 `FirstCraft`。

0.3 的普通生存入口是 `voyage/NavigationSlate`、`client/FirstFlightScreen`、`compat/rocketry/OriginalShipyard` 和 `client/ChartScreen`。`compat/galacticraft/OriginalStarMap` 从实际载入的原天体注册表读取目的地，`orbit/OrbitModule` 推进太空航行，`transit/HullTransit` 交接实船。涉及 `PrototypeVoyages`、`campaign` 或 UnBound 自有行星的旧记录对应 0.2 原型。

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

`client/FirstFlightScreen.java` 接待未绑定终端，也可从 `client/ChartScreen.java` 返回。图格和可用键盘选择的材料行共用 `FirstFlightScreen.PartButton`，按 `shipyard/LaunchPadPlan.pick` 区分俯视与侧视；输入边界测试在 `client/shipyard/LaunchPadPickingTest`。舰桥配方页读取本世界同步的有序配方，配方书在收到铁锭后解锁舰桥。`qa/habitat-ui/src/main/java/dev/starbridge/qa/habitat/FirstCraft.java` 在无作弊的原版工作台验证开启配方书条目、合成和放置舰桥。`tools/package_dev_profile.py` 按锁定清单生成两侧附属的安装 ZIP。

短时舰队在 `qa/connected-fleet`。`ConnectedFleet.java` 记录三条巡航、分档刻耗时和熔炉产量，`FleetPeers.java` 在一个 Forge 进程中建立独立游戏连接，`audit.py` 交叉核对服务端和客户端记录。复现见 `qa/connected-fleet/orbital-run.md`。

近地测高仪用 `VsShipBackend.surfaceClearance` 对实际船体世界包围盒中心下方的已加载地表测距；`PilotModule` 传输读数及垂直速度，`PilotScreen` 绘制。实测通过同一新存档的 qa/habitat-ui 触地场景。

返航收费恢复在 `transit/HullTransit.java` 的 `paidCheckpoint`、`PassageStorage.java` 和 `TransitJournal.FareProof`。新航程按两端实际船体区块的交易印记核对，旧档用 `LegacyPaidHull.java` 对照原组装机档案。库存以已落盘的船体为准。`qa/original-flight/restart_server.py` 在两个服务进程之间保留证据，`inspect_restart.py` 读离线区块，`RestartCheck` 在两名客户端重登后核对船员、货物和原收据。

着陆仪在 `client/orbit/OrbitScreen.java`。`orbit/LandingControl.java` 限制每艘船和整服的测绘工作，`LandingSurvey.java` 提供地形图和着陆前的占用检查。`OrbitModule.finishLanding` 与 `Voyages.land` 将船体、速度和能源复核失败发送给当前着陆仪，保留其地图所选位置。`LandingGround.java` 与 `transit/BerthPlanner.java` 均按目的地方块碰撞面计算高度。原世界的移动高度表可能漏掉月岩。附属测试用例为 `qa/original-flight` 的 `LandingCheck` 和 `LandingCheckClient`。

`mixin/Vs2411WatchHandoffMixin.java` 只在 VS 2.4.11 运行。原生跨维时，同一船体会在一刻内同时进入活动和退场名单，兼容层让活动名单在下一刻再订阅这艘船。版本开关在 `mixin/OriginalWorldMixinPlugin.java`。

`qa/connected-fleet` 的 `FleetPeers` 和 `FleetObserver` 分别驱动回环 Forge 协议连接与完整渲染的船长，`FleetScenario` 核对原船、货物、工作负载和月球往返，`FleetRecovery` 检查独立复制的中断存档。复现见 `qa/connected-fleet/native-hulls.md`。

首航船坞俯视、侧视和原组装机分层示例共用 `client/shipyard/LaunchPadPlan.java`。空中使用未绑定终端打开 `FirstFlightScreen`，玩家能在搭组装机之前看到原方块、数量、高度和朝向。

`qa/original-flight` 的 `FlightDamageTrace` 只读记录原侧与宿主伤害。同目录的 `RadiationEquipment` 核对 GalaxySpace 熔合机和高级火箭化学反应器配方，`audit_solar.py` 比对对照组与防护组。

`transit/ArrivalDeck.java` 与 `HullTransit` 共同核对实际甲板位置和稳定站立的乘员，只有记录过这种到达的乘员才进入交付列表。`qa/original-flight/inspect_deck.py` 可从已停服的 releasecrew4 存档读取 VS 原生变换、甲板和月岩，区分站在箱顶和站在月面。

`ChartScreen.open` 将同船的图表回复交给已打开的 `LunarKitScreen`，返回时沿用父星图所选的目的地。双客户端 `BerthPilot` 在装备图渲染后重放原星图回复。月面装备原容器由 `compat/galacticraft/OriginalOxygenEquipment.java` 打开。`client/EquipmentAccessScreen.java` 为月面页与首航终端提供个人装备按钮，`voyage/VoyageNetwork.java` 只接收请求序号，服务端玩家来自当前连接。双人实机断言位于 `qa/original-flight` 的 `equipment/EquipmentCrew` 与 `EquipmentCrewClient`。

`client/shipyard/StarterHullPlan` 同时提供原机的六层示例和首航备料数量。`FabricationRecipes` 从当前世界同步的配方读取输入、产量与工作设备，`FabricationScreen` 可点选原料并回退。实际点击见 `qa/habitat-ui` 的 `MaterialsCheck`。地狱疣与烈焰棒的来源提示及打火石配方入口也在 `FabricationScreen`，粗制药水到打火石再返回用 `MachineRecipeJourney` 验证。

`survey/FieldNotes` 把船位和勘察点按维度存入当前终端。坐标由服务端取玩家脚下。`client/survey/SurfaceSketch` 分批读取客户端已收地表，`SurfaceSurveyScreen` 显示方位、距离、相对高度和深坑。QA 入口是 `qa/habitat-ui` 的 `FieldCheck`。

`orbit/LandingControl` 将同刻确认分配给实际测绘任务，并为正交给 VS 的船短暂保留范围。`qa/concurrent-orbits` 的 `BerthPilot` 从舰桥和中文着陆仪点击，`ConcurrentBerths` 核对同刻泊位、收据与货物。`check_soak.py` 读取连续运行记录，`record_soak.py` 冻结原始窗口并在停服后核对两端安装与实际时长。

月面长船的返航由 `transit/SurfaceLiftChunks` 分批加载超出船长视距的船体，和 `Voyages.depart` 一起在起飞前再次核实船长、目的地和费用。复现见 `qa/connected-fleet/native-hulls.md` 与 `audit_large.py`。

`qa/concurrent-orbits` 的 `FirstVisit` 只读核对扩展世界所选区块首次访问，记录测绘回复、乘员切到目的地与交付的单调时钟时间。`audit_berths.py` 从停服后的区域文件读取原船库存与收据，`check_marker.ps1` 独立检验中文着陆仪大地图的实际截图。

勘察图 `client/survey/SurfaceSurveyScreen.java` 选中飞船或勘察点并关闭后，`client/survey/WalkingBearing.java` 从手里的同一终端读取当前维度的已记录位置，在视野内绘制方向、距离或高度差。`qa/habitat-ui` 的 `FieldCheck`、`WalkingHudCheck` 检查实际走动和指针像素。 `SurfaceSketch` 采样已加载区块的露天地表，`SurfaceSurveyScreen` 在最近一级绘制相邻格的高差。`SurfaceCluesScreen` 通过 `ObservedRock` 辨认准星下的岩面。原月球客户端用 `qa/original-flight` 的 `LunarWalker` 和 `SurveyRoute` 验证缩放、悬停和亮线。 `qa/habitat-ui` 的 `RockSampleCheck` 在独立主世界，用同一终端依次辨认可见、遮挡与空旷视线。

首航补给的三张图由 `client/LaunchSupplies.java` 绘制，`block/ShipCoreBlockEntity.java` 将加油后的容器归还玩家。CC 的 `ShipPeripheral.attach/detach` 把真实设备登记到舰桥的 `AttachedComputers`。`VoyageNetwork` 检查船主与设备是否仍在线，`ChartScreen` 和 `ComputerCard` 展示可点选设备。语言键、游戏内入口与测量见 `qa/bridge-controls/README.md`。

`client/shipyard/FabricationScreen.java` 的「车间配方」从材料图或首航供电页进入。`FabricationRecipes.java` 读取当前世界配方，对已修复的原压板器和隔热砖优先显示对应输入。`qa/habitat-ui` 中的 `MaterialsCheck` 逐项点材料，`WorkshopCraft` 用鼠标选原版配方书与工作台成品格。

`compat/rocketry/OriginalFabrication.java` 读取 LibVulpes 实际登记的加工批次。没有工作台配方时，`VoyageNetwork` 按原物品和变种查找机器配方。`MachineRecipeJourney` 在中文生存终端点导航电脑、跟踪芯片和钛板，打开原机器摆法再回到原配方。摆法沿用 `client/MachineAssemblyScreen.java`，`compat/rocketry/OriginalMachineLayout.java` 读取原控制器的结构和可用端口。

`compat/rocketry/OriginalPropellant.java` 从专服或集成服实际加载的原机配方读取流体批量，`client/PropellantScreen.java` 从「火箭加注」显示流向和原控制器配方。已关闭或切换到其他页面时，旧查询的回复由请求编号丢弃。

舰桥选原月球后，`client/LunarKitScreen.java` 打开 `client/LunarRationsScreen.java`，以宿主当前工作台配方、原变种 `LegacyMeta=18` 和玩家库存绘制野外口粮。`qa/habitat-ui` 的 `RationsCheck` 点击并核对实际配方。

原压板器的摆法和通电预览在 `client/shipyard/PressSetupScreen.java`。`FabricationScreen` 在压板器或原锡板上提供入口。`qa/habitat-ui` 的 `PressCycle` 点击示意图并在游戏世界放置、启动原机器，再从实际服务端核对消耗和产物。

舰桥星图与未配对终端共用 `client/survey/SurveyButton.java`。地图查询由 `survey/FieldNotes.java` 的服务端读取当前手持终端，`SurfaceSurveyScreen` 检查回复是否仍属于当前菜单，返回时恢复原来的页面。中文实机点击、空手提示及过期回复用 `qa/habitat-ui` 的 `ChartSurveyCheck` 核对。

`client/BucketTransferDiagram.java` 绘制原输入口、输出口的上格放桶和下格取件。`FabricationScreen.open(Screen, ItemStack)` 保留原机口的 `LegacyMeta`。`qa/native-fuel` 用原机器、两端游戏存档和真实客户端操作验证一整桶推进剂。

`ShipyardWire.sendPacked` 从 `EntityRocket` 读取实际油量、容量和原船推力、自重，`ShipyardScreen` 在组装后显示发射准备。`qa/native-fuel/run_windows.py --inspect-from` 可以在已加注原存档的副本核对。

`compat/rocketry/OriginalMachineLayout` 读取原控制器的 `getStructure` 和 `getAllowableBlocks`。`client/MachineAssemblyScreen` 让玩家逐层点选部件，按原物品和变种汇总结构中的数量，再带入 `client/shipyard/FabricationScreen` 的库存和制作批次。`qa/habitat-ui/audit_layout.py` 将界面中每一格对应到实际生产存档。

`client/shipyard/PressSetupScreen` 中，未通电时的锡块图标和常驻的「锡块配方」按钮都把带原模组变种标签的物品交给 `FabricationScreen`。`PressCycle.diagram` 核对两种点击、空输入格及返回时的通电状态。

`WorkshopAssembly` / `WorkshopHands` 位于 `qa/native-fuel`，使用普通客户端物品选择和方块点击验收两台原机的搭建与激活。运行入口 `run_windows.py --player-build`。


同档生存角色取得未绑定终端后，`qa/survival-expedition` 的 `ExpeditionClient` 将 `readslate` 交给 `FirstGuide`。该用例通过绑定的使用键开图、点击原界面的两种视图并保存操作结果。

舰桥「航线校准」从 `client/ChartScreen` 打开 `client/NavigationProofScreen`。`compat/galacticraft/OriginalNavigationTech` 按真实注册物品和变种生成清单，`voyage/NavigationCalibration` 重新核对船主、航线与物品槽，`VoyageNetwork` 同步结果。专服与界面用例分别在 `qa/navigation-calibration` 和 `qa/habitat-ui/.../NavigationChartInput.java`。

玩家在地表勘察图点格子，`client/survey/SurfaceSurveyScreen` 打开 `SurfaceProfileScreen`。`SurfaceProfile` 从两条地表采样线给出东西、南北剖面。`SurfaceSketch` 可标记列顶露天原砖，`ObservedRock` 和准星共用原方块的材质辨认。`qa/lunar-field` 用独立托管客户端验收剖面、徒步和亲眼辨认地牢砖。`FieldCavern` 驾驶角色探索通道，`FieldVessel` 核对同船带钥匙返航。

发射台地面由 `compat/rocketry/PadFloor` 检查两种原铺面。`ShipyardWire` 传回首个缺口，`client/shipyard/ShipyardScreen` 在可旋转的地面层画叉。`RocketTransfer` 开始装配和切割前重新勘测。

当高级火箭原组装机返回 INVALIDBLOCK，`compat/rocketry/ForbiddenHullBlocks` 在实际船体范围内比对原禁用清单。`ShipyardWire` 将层、方块名和位置传给可旋转的船坞图，`qa/shipyard-load/check_forbidden.py` 验证专服，`qa/remote-ui/replay_forbidden_dock.py` 离线核对远端生存操作。

太空驾驶席上的「舰船雷达」使用 `OrbitWire.Contact` 的服务器船号、航速和船长，`client/orbit/TrafficBearing` 计算会合速度和方位，`TrafficScope` 处理点选与失联，`OrbitScreen` 保留原来的行星航线。

非船主在航行中的舰桥右击会打开 `client/orbit/CrewFlightScreen`。由 `orbit/CrewWatch` 在普通船员仍在这艘船上时发送状态。`HelmIssuer` 标识下令的舰桥、CC 电脑或 MCP，随 `SurfaceLiftChunks` 的延迟起飞请求重新核验权限。

长船确认走 `orbit/LandingControl.confirm` 和 `LandingSurvey.work`。专服的 `qa/home-berths/src/main/java/dev/starbridge/qa/home/WideApproachCheck.java` 从已审计原船存档通过正常服务端入口勘测、确认。`qa/home-berths/audit_wide_approach.py` 独立回放原区块、货舱和船长。

落实月面落点后，`transit/CabinApproach.java` 在跨维前用 `transit/ApproachChunks.java` 分批准备物理存储区块，以原版进度条向船员显示进度，并在跨维或取消准备后释放区块。`qa/home-berths/audit_wide_approach.py` 回放区块、货物、玩家及扣费。

`qa/home-berths` 的 `ParallelWideApproach` 让两名模拟船长在同一刻触发两艘 300 格舰的近地交接。`run_wide_capture.py --parallel-approach` 从测试入口直接交接。`--parallel-survey` 模拟着陆仪持续勘测和同刻确认。`audit_parallel_approach.py` 从关服区块逐格比对两艘船和收费凭据。真实双客户端用 `qa/concurrent-orbits/hosted_wide.py` 和 `ConcurrentBerths` 的托管宽舰模式。`--audit-saved` 读关服区域。`qa/concurrent-orbits/LandingSamples.java` 用 JDK 17 读取专服 JFR，按 `VsShipBackend.assembleCabin` 过滤生产装配并计数 VS 的源码行。测试夹具 `SourceRemovalTiming` 观察原舱两次清除遍历，`TemplateTiming` 与 `TemplateChunkTiming` 只在原生装配的模板放置期间计时，并读出写入前的区块就绪数。`WideAssemblyPreload` 在托管测试中用四区块每刻的预算预热这两个已知地块。`LandingStorage` 在 VS 2.4.11 从分配器副本预览即将使用的地块并按批预热。`--reserve` 在托管测试中验收实际模板范围和预测是否一致。实际组装由 VS 原生创建船，旧的空船登记可在重启时清理。VS 2.4.11 的 `VsShipBackend.assembleCabin` 使用实际模板网格定位船芯，并仅在已加载区块读取 UUID。`AssemblyChunkFutures` 和 `VsAssemblyChunkMixin` 只在大型 VS 装配的同一服务端线程复用已完成的 FULL 区块请求，跟随区块缓存失效。`CabinApproach` 最多同时为两艘船准备区块，`ApproachChunks` 共享每刻四个区块的请求预算。

月面续档的 `RoomTrail` 从角色自己曾走过的相邻脚印拼出可重复核对的路径。`FieldPilot` 将结果交给既有的可见性与脚下支撑检查。

`qa/lunar-field` 的 `FieldBossDrop` 读取原首领死亡进度，在服务器上关联实际攻击、原版掉落及钥匙实体。`FieldTreasure` 观察原箱子解锁和实际打开的原菜单，`FieldPilot` 在钥匙消耗后继续操作同一个箱子。`run.py` 和 `replay.py` 复核解锁、原库存和载货。

`TreasureHands` 从 `field-vessel` 的登记舰桥找到原货箱，用可触及的面发出实际交互，从玩家槽移入原奖励。`field-cargo-input`、`field-cargo-slot` 为客户端操作记录，`field-cargo` 仍由原船货箱和玩家库存共同确认。

月面 `qa/lunar-field/replay.py --world` 从原始关服归档重新读取玩家、货箱、舰桥和收费票据，复核运行报告。

`FabricationScreen` 在工作台配方内用 `FabricationStock` 按背包数量分配配料，优先确保只能用单一原料的格子有材料。Retrofit 配方保留原物品变种匹配。托管 `MachineRecipeJourney` 使用带有正确和错误变种的背包验证导航电脑的九格配方。

`FieldCavern` 在井口地表补桥时可把随身原月面地牢砖换到快捷栏，随后通过正常放置操作走向可见岸边。地下旧补桥保留原有圆石路径。

`FabricationScreen.resultDetails` 在示例材料页根据背包成品和实际配方产量显示剩余批次。`MachineRecipeJourney` 在原塔架页检查携带两格时是否还需四批，再从车间图标打开电弧炉的配方和原五层摆法。

`OriginalFabrication` 读取 `BlockPress` 的注册配方。`PressSetupScreen` 既能显示默认锡板示例，也能从原粉末配方显示原矿、拉杆和实际批次产物。

`FabricationRecipes` 以 Forge 的酿造结果核对隔热砖所需的三条酿造路线，`MachineRecipeJourney` 从原结构逐级点击带正确药水标签的材料。

`client/shipyard/PressSetupScreen.java` 从当前原机配方读可加工方块，优先选背包已有的变种。`FabricationStock.owned` 按实际物品和变种计数。`MachineRecipeJourney` 从原炉控制电路走到压板器，在中文客户端切换两种铜矿并复核预览。

原压板器的金矿采集指引在 `client/shipyard/FabricationScreen.java`。可直接点附魔台、讲台或铁砧查看配方。`qa/habitat-ui` 的 `MachineRecipeJourney` 从原金粉配方检查讲台的入口和返回。
