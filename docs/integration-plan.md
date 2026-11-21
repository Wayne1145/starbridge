# Starbridge 融合大纲（Forge 1.20.1）

2026-11-21。Starbridge 的角色是联动附属。玩家在高级火箭（Advanced Rocketry）的发射台上搭自己的船，用原组装机扫描、装配、加注并起飞。船由玩家亲自驾驶，目的地由 Galacticraft、ExtraPlanets 和 GalaxySpace 实际提供。星图、着陆系统与自动驾驶都服务于这趟旅程。0.2 原型及其测试另见[历史记录](unbound-roadmap.md)。

验收首航时，船长和船员以普通生存身份操作。从哪一个方块或物品打开界面也属于测试，例如返航时船长要亲自使用已配对的航图终端，再在星图选择母港。由测试服代开界面只能验证界面内的后续操作。

## 先验证原包组合

| 顺序 | 依据与实测目标 |
| --- | --- |
| 原版月球 | 首选在隔离的 Forge 1.20.1 专服和两个对应客户端使用 Retrofit `0.6.2-beta`。兼容层作者记录了 Galacticraft 3.0.12（Core 和 Planets）的月球、火星、小行星带、空间站以及原火箭发射。它运行的是 1.7.10 模组原包，宿主游戏仍为 1.20.1。先核对实际载入的维度键、原世界生成器、自然生成的地牢、进化骷髅 Boss、一级钥匙和原宝箱。上游报告与本项目实测分别记录。 [兼容层作者测试表与局限](https://github.com/Astryxx/Retrofit#compatibility) · [原版月球地牢与钥匙的游戏内说明](https://github.com/micdoodle8/Galacticraft/blob/master/src/main/resources/assets/galacticraftcore/lang/en_US.lang) |
| 经典造船 | 高级火箭作者提供 1.7.10 原包，依赖 LibVulpes。优先验证这两个原包能否在同一宿主正常显示组装机界面、扫描塔架内的玩家方块并发射。将原船与宿主维度注册表中的真实月球关联，再对接太空驾驶。 [上游发行和前置](https://www.curseforge.com/minecraft/mc-mods/advanced-rocketry) · [经典组装机的界面与结构检查](https://github.com/Advanced-Rocketry/AdvancedRocketry/blob/1.12/src/main/java/zmaster587/advancedRocketry/tile/TileRocketAssemblingMachine.java) |
| 两个行星扩展 | 两者都有 1.7.10 原包，作者发行页均未列 Forge 1.20.1 原生版。分别加载并验证一个有辨识度的原世界、玩法和奖励，再按 ExtraPlanets 作者的 GalaxySpace 共存选项合装。核对维度 ID 冲突、天体归属、双方装备和科技前置。分版本列出行星、Boss 和掉落，查清 1.7.10 原包与玩家熟悉的 1.12.2 内容差异。 [ExtraPlanets](https://www.curseforge.com/minecraft/mc-mods/extraplanets) · [GalaxySpace](https://www.curseforge.com/minecraft/mc-mods/galaxy-space-addon-for-galacticraft) · [作者的共存设置](https://docs.mjrlegends.com/ExtraPlanets/1.7.10compact/) |
| 太空驾驶 | UnBound 作者展示了转向、加速、锁定目标与距离反馈。已只读核对它的 Forge 0.16.1 发行包，公开方法 `Travel.addDestination` 和 `DestinationSpec.dimension` 可以指定外部目的地。样机先试这个接口能否指向宿主中由 Galacticraft 生成的月球，再试驾驶器能否接受任意方块船。0.16.1 的发布说明提到星舰模型。其太空段船体坐标是否固定仍待核实。 [驾驶指南](https://moltea.github.io/unbound-wiki/zh/docs/guides/ships/) · [附属开发指南](https://moltea.github.io/unbound-wiki/ru/docs/addons/) · [核对的 Forge 发行包](https://www.curseforge.com/minecraft/mc-mods/unbound/files/9697686) |

Retrofit 这一版只接收 1.7.10 旧包。若用它无法保留关键的 1.12.2 内容，可以另测 Retromod 在 1.20.1 上加载 1.12.2 原包的能力，当前缺少 Galacticraft 4 与高级火箭在这个组合下的实测。若需移植，再单列具体上游、版本、代码和素材授权。ExtraPlanets 与 GalaxySpace 均有自己的修改和发布条款，计划优先使用从作者渠道取得的原包。发布融合版时提供锁定版本的整合包清单，列出客户端与服务端所需的上游、前置和兼容层。[Retrofit 使用范围](https://github.com/Astryxx/Retrofit#using-it) · [Retromod](https://github.com/Bownlux/Retromod) · [ExtraPlanets 条款](https://docs.mjrlegends.com/Other/permissions/) · [GalaxySpace 条款](https://www.curseforge.com/minecraft/mc-mods/galaxy-space-addon-for-galacticraft)

另外两项已有工作会影响备选方案。高级火箭 Community Edition `v0.9.0-beta.1` 是 Forge 1.20.1 的非官方重写。该标签的扫描从组装机正上方沿相连方块进行，单船上限 2,048 个方块，飞行规划器只接受主世界、自带月球和空间站。官方 Galacticraft 5 先开发 Fabric，GC4 扩展需适配新的接口。[社区版发布](https://github.com/sunthemoon/AdvancedRocketry-Community/releases/tag/v0.9.0-beta.1) · [扫描入口](https://github.com/sunthemoon/AdvancedRocketry-Community/blob/v0.9.0-beta.1/src/main/java/io/github/sunthemoon/advancedrocketrycommunity/rocket/server/RocketManager.java#L585-L590) · [方块上限](https://github.com/sunthemoon/AdvancedRocketry-Community/blob/v0.9.0-beta.1/src/main/java/io/github/sunthemoon/advancedrocketrycommunity/rocket/RocketLimits.java#L6-L8) · [目的地判定](https://github.com/sunthemoon/AdvancedRocketry-Community/blob/v0.9.0-beta.1/src/main/java/io/github/sunthemoon/advancedrocketrycommunity/rocket/flight/RocketFlightPlanner.java#L151-L156) · [GC5 官方说明](https://github.com/TeamGalacticraft/Galacticraft#common-questions)

## 玩家要走通的旅程

1. **第一次建船。** 玩家从配方或组装机打开可旋转、可逐层查看的结构图，在发射台上立塔架、摆自己的船体、引擎、油箱、导航和座椅。原组装机保留扫描与组装操作。预览和读数解释装配问题，例如漏装引擎时在哪里补装、起飞还缺多少推力。加注后登船，在舰桥星图锁定已解锁的月球。
2. **开船入轨。** 驾驶席切到第三人称跟船视角，显示自己搭出的三维轮廓。服务器推进每艘船在同一片太空中的坐标、朝向和速度。驾驶员可以转错方向、减速或与另一艘船会合。设计上把舱内方块放在固定、正常运转的位置，另一名玩家能下楼开箱、操作正在工作的机器。样机需要验证舱内的玩家交互与船外视角能同时成立。
3. **手动降落和返回太空。** 接近目标时预加载对应上游世界的落点。着陆仪显示高度、下降率、落点范围与障碍，准备好后由 Valkyrien Skies 接管近地运动、碰撞和着陆。起飞后反向交接。各阶段始终只有一份可操作的船舱、货物和机器。用船号、船员舱内相对位置、燃料与熔炉进度核对。落点被占可复飞，切换中断可恢复。分别测驾驶员看到的连续性和服务器最慢的一刻。
4. **完成一次原版登月。** 在干净生存存档里，两位普通玩家用小船从造船走到月球地牢。由原世界自然生成地牢和 Boss，两人击败进化骷髅，拿一级钥匙开原宝箱，再将战利品装入同一艘船飞回出发地。第一次可玩交付要走通这一整趟。后续从两个扩展各选一个原目的地，星图显示天体来源、着陆条件与未解锁原因，验证原掉落或图纸能继续推进下一阶段。
5. **扩到多人母舰。** 高级火箭 1.12 经典装配机的扫描范围为水平最大 16×16 格、高最大 64 格。保留原装配操作，为长船另做船坞扩展。按船长 100、200、300 格分档，注明宽、高、非空气方块及运行的机器数。在单人、局域网、10–30 人长期专服检验多舰并发、多人分处不同世界、掉线和重启。以 20 TPS 为目标记录常规刻耗时、装配与着陆的最长停顿及客户端帧时间。Retrofit 作者注明旧世界生成串行进行，要单独测多人首次接近不同星球的情况。 [经典装配机源码](https://github.com/Advanced-Rocketry/AdvancedRocketry/blob/1.12/src/main/java/zmaster587/advancedRocketry/tile/TileRocketAssemblingMachine.java) · [兼容层世界生成说明](https://github.com/Astryxx/Retrofit#what-works)
6. **接通自动驾驶。** 舰桥用星图设航点、巡航和返航，驾驶员可随时刹车、取消或接管。适配 CC:Tweaked 与船主授权的可选 MCP/AI，用同一套驾驶操作和权限检查。用图形界面完成一次自动巡航并人工接管，再分别验收两种外部控制方式。

2026-11-22 的原月球分段实测已走通自然首领、原钥匙和原宝箱界面。用例直接把装备好的玩家带到首领房，整船抵达和返航继续按双人全程验收。[原月球玩家实测](original-runtime-verification.json)

## 图形界面验收

普通生存玩家在关闭作弊、没有管理员权限的情况下，仅通过游戏内方块、界面和可改绑按键，能找到下一步并完成整趟航程。关键信息在操作的位置出现，玩家能从界面发现问题并修正。命令用于管理与开发诊断。验收覆盖真实客户端里的可见操作。任何一步仍要求玩家输入聊天命令或查控制台，就继续完善相应界面。

| 玩家打开或使用的界面 | 验收情形 |
| --- | --- |
| 组装机与分层预览 | 新玩家在还没有组装机时，可从终端的[俯视图](images/first-yard-plan.png)和[侧视图](images/first-yard-elevation.png)搭出示例发射台。故意漏装引擎后，界面指出动力缺口，补装后可点击组装。 |
| 加注站与登船 | 玩家能分清油箱容量、已加注燃料和原机供电。在加注口看到当前和所需液量，满足条件后在座椅或舰桥看到可用的起飞操作。 |
| 舰桥星图 | 在星图查看行星所属模组、进入条件、航线、燃料与返航储备。选月球后可点[月面出舱装备](images/moon-equipment.png)，查看当地原模组的供氧和防护装备。任一船员可从未绑定终端打开自己的原供氧装备栏，用物品栏直接穿戴。选中天体后可从驾驶席起飞。 |
| 驾驶席与着陆仪 | 巡航时抬头看见速度、目标方向、距离与燃料。近地时显示高度、下降率和落点允许状态。玩家能看到按键提示、改绑并随时接管。 |
| 地表勘察与返船 | 船员在甲板用航图终端记录船位，在勘察图选「飞船」。关图后手持原终端，转过身仍能看懂返船方向和距离。换回工具，视野要恢复干净。玩家在自己发现洞口后可以记录勘察点。 |
| 舱内供氧 | 右击供氧器查看舱室密封和剩余储量，直接从背包补气。开舱后能从仪表判断失压，关舱后读数与角色的呼吸恢复一致。[原月球双人实测](original-crew-verification.json) |
| 船员与机器 | 一人驾驶时，另一名玩家能走到另一层甲板打开箱子和机器自己的界面。两人可以在同一艘船上带着原地牢奖励返航。 |
| 船主授权与接管 | 船主从舰桥的控制页点选已连接的电脑进行配对，可在同一页撤销或开启 MCP 授权。航行中可以一键接管，其他船员能看到当前由谁驾驶。 |

首航验收从新玩家在配方书发现航图终端、打开船坞图解时算起。不给新玩家命令表或外部说明，观察他能否在漏装引擎时找到问题所在的层、补装后重新扫描，返航后还能在原船箱子里找到自己的货物。记录每一步实际使用的入口、看到的反馈和修正动作。船主配置自动驾驶与外部权限也适用同一标准。

验收时让新玩家从可合成的物品或原装配机找到第一步。缺了导航电脑，用零件图标显示所需部件；平台有洞，在对应层标出那一格；有引擎但推力不够，就显示实测推力和所需推力。能自由摆放的零件只展示安装示例。把反馈放在玩家接下来可以操作的位置，未能组装时保留船体和物品。遇到已知无法搬运的机器，船坞应能选中它所在的层并标出具体方块。[原机扫描与装配对照](shipyard-verification.json)

星图支持点选目的地与返航港，着陆仪在地图上选点。以着陆仪为例：仪表应画出船体占地，选到世界边界外或泊位被占时，在原图标出原因并停用降落按钮。待勘测的点要与确认安全的点区分开。玩家可直接改选或复飞；提示使用客户端语言。键位提示读取玩家当前绑定，改键后同步更新。允许查看额外文字和精确坐标。每次验收要注明游戏版本、客户端人数、实际点击或按键、改错后的反馈，以及服务器有没有代为准备或执行。只操作控制台、直接调用方法或看到一张截图，都不能证明普通玩家走通了全程。

0.2 旧测试可以复用乘员与货物核对方法。它测过 52 方块双人飞往 UnBound 月球再返回。100 格旧物理船走过短航点，300 格试飞超时。0.3 的原月球载货往返记录见下方。30 人长期服和多艘船同时降落要以各自的记录验收。[旧测试报告](test-report.md) · [原始场景说明与结果](expedition-verification.json)

## 首航进展（2026-11-22）

原组装机用 RF，原火箭加液体推进剂，舰桥从 FE 储能支付航费。终端的「起飞补给」展示每种补给的流向和操作入口。一个无作弊的中文单人客户端从原发电机投煤，观察到原组装机从 0 升至 2,040 RF。岩浆桶给舰桥充入 200,000 FE 并退回空桶。[补给界面与实测条件](launch-supplies-verification.json)

长船的验收包括客户端手感。100、200、300 格窄船在单人载货回港时都实测到了明显卡顿。初测返航驾驶画面每帧中位时间分别为 752、703 和 867 毫秒，货物与站位则均通过。修复要用同一航段重新采样，再看局域网访客看到的画面。[帧间隔与测试场景](integrated-fleet-verification.json)

玩家取得指南针后，配方书会解锁航图终端；取得铁锭后解锁舰桥控制台。在无作弊的单人新档中，角色用原材料实际合成了两件物品，点开「舰桥配方」查看九宫格，放置舰桥，绑定后从星图返回首航图解。0.3 双人样船已在原月球按实际地形图改选泊位，33 个船体方块中最低处与月岩碰撞面实测相距 0 格，两名船员随后载货返港。[着陆仪、复飞与实测](landing-instrument-verification.json) 两次双人专服已带回原月球宝箱奖励；预置条件包括船体、能量和地牢位置。[首航证据](first-flight-and-lan.json) · [双人航程](original-crew-verification.json)

舰桥的电脑、MCP 授权与人工接管已用实际 CC 电脑和 MCP 进程测试。三艘固定船舱的 10、20、30 个连接已各巡航 1,200 刻。之后的全扩展联飞由两名普通生存玩家完成，载回了原月球宝箱中的命名牌。另一场 85 秒测试在 30 个连接下完成了一艘 100 格船的往返，两端都确认船长站在原船甲板上。进一步的 30 连接短测已让 200 和 300 格长的原船分别往返原月球，测量跨度约 100 和 110 秒。新档自行备料造船、原月球徒步寻找地牢和专服多日运行正在验收。双舰同刻申请原月球泊位已经走通，被拒的一方可直接在着陆图改选。[双舰靠泊与长期运行](concurrent-orbits-verification.json) · [200 与 300 格原船](large-roundtrips-verification.json) · [全扩展登月](original-radiation-verification.json) · [百格船往返](native-fleet-verification.json) · [电脑控制实测](computer-flight-verification.json) · [三舰连接短测](connected-fleet-verification.json)

从「材料与配方」点「车间配方」可查看小型压板器、原电池、燃煤发电机和隔热砖。隔热砖图上写明所需的两种药水。玩家能从发电机点进电池配方，再回到先前查看的塔架。关闭作弊的中文单人客户端还在原版工作台的配方书中搜索小型压板器，取走了成品。[车间界面和工作台取件记录](workshop-verification.json)

舰桥点选 CC 电脑的专服实测中，船主从实际设备列表配对，解除，再次配对。服务端观察到了四次状态变化，人工接管时 Lua 和 MCP 的制动请求均收到拒绝。设备列表刷新回复到达时，船主已经关闭的界面继续保持关闭。[设备列表与实测输入](device-picker-verification.json)
