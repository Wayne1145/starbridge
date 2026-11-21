# Starbridge 融合大纲（Forge 1.20.1）

2026-11-21。Starbridge 的角色是联动附属。玩家在高级火箭（Advanced Rocketry）的发射台上搭自己的船，用原组装机扫描、装配、加注并起飞。船由玩家亲自驾驶，目的地由 Galacticraft、ExtraPlanets 和 GalaxySpace 实际提供。星图、着陆系统与自动驾驶都服务于这趟旅程。0.2 原型及其测试另见[历史记录](unbound-roadmap.md)。

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

## 图形界面验收

普通生存玩家在关闭作弊、没有管理员权限的情况下，仅通过游戏内方块、界面和可改绑按键，能找到下一步并完成整趟航程。关键信息在操作的位置出现，玩家能从界面发现问题并修正。命令用于管理与开发诊断。验收覆盖真实客户端里的可见操作。任何一步仍要求玩家输入聊天命令或查控制台，就继续完善相应界面。

| 玩家打开或使用的界面 | 验收情形 |
| --- | --- |
| 组装机与分层预览 | 新玩家凭游戏内结构图搭出发射台。故意漏装引擎后，界面指出动力缺口，补装后可点击组装。 |
| 舰桥星图 | 在星图查看行星所属模组、进入条件、航线、燃料与返航储备。选中天体后可从驾驶席起飞。 |
| 驾驶席与着陆仪 | 巡航时抬头看见速度、目标方向、距离与燃料。近地时显示高度、下降率和落点允许状态。玩家能看到按键提示、改绑并随时接管。 |
| 船员与机器 | 一人驾驶时，另一名玩家能走到另一层甲板打开箱子和机器自己的界面。两人可以在同一艘船上带着原地牢奖励返航。 |

0.2 旧测试可以复用乘员与货物核对方法。它测过 52 方块双人飞往 UnBound 月球再返回。100 格旧物理船走过短航点，300 格试飞超时。此次融合、300 格母舰及 30 人长期服都待单独实测。[旧测试报告](test-report.md) · [原始场景说明与结果](expedition-verification.json)
