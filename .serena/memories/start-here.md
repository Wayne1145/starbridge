# Starbridge 项目交接

目标是 Forge 1.20.1 联动附属。高级火箭的原发射台、塔架、组装机、推进剂、座椅和实体负责发射。Galacticraft、ExtraPlanets、GalaxySpace 自己提供星球、世界生成、地牢、首领、钥匙和科技图纸。默认星图只登记实际提供方。游戏保持 1.20.1，以 Retrofit 的 1.7.10 运行环境加载锁定的原包。0.2 原型在可选历史资源包和历史文档中。

**玩家界面是一项发行验收条件。** 普通生存玩家关闭作弊，能在对应方块上找到入口，通过可点选界面、方块和可改绑按键完成造船检查、扫描、装配、加注、登船、选星、手动或自动驾驶、选落点、供氧、返航和人工接管。界面提示显示真实绑定、当前状态和解决办法。多人船员能在固定的多层船舱照常活动和用机器。船主在舰桥授予和撤销电脑、AI 的控制权。测试记录区分点击界面的真实客户端、模拟玩家、控制台和预置条件。这条规则也适用于以后新增的生存玩法。例如着陆仪要在玩家确认前用地图和当前语言指出不可降落的区域，并让玩家就地改选。测试时应能从界面看出下一步可做什么，以及操作后发生了什么；只有文字说明或聊天命令的步骤仍待补齐。

图形验收从玩家自己找到并打开入口算起。以返航为例，船长在月球用已配对的终端打开星图、选母港并确认着陆。测试服直接推送星图时须记录这个预置。

后台设计以分段保持同一船和一份库存为原则。太空时正常运行的船体留在固定船舱，驾驶员第三人称看见自己搭的船，服务器推进航行坐标。近地交给 Valkyrien Skies，反向起飞恢复固定船体。船长 100、200、300 格要分别记录宽高、实际方块、机器和每阶段耗时。单人、局域网、10 至 30 人长期专服要分别实测。测试只使用隔离实例和后台桌面。

**当前开发版与下一轮验收（2026-11-22）。** `0.3.0-dev` 主包 `e1927ec3` 的全扩展双人登月、个人氧气装备栏三个场景和 30 连接下的 100 格原船往返均通过。实际输入、测试预置和延迟见 `docs/original-radiation-verification.json`、`docs/original-oxygen-equipment.json`、`docs/native-fleet-verification.json`。单人关作弊用配方书合成、内网加入和电脑接管另有单独场景。下一轮从新档自行收集材料、照首航图建船、探索地牢并返港，再验证 200 和 300 格船实际往返、多人多舰同时着陆与多日运行。10 至 30 人是长期目标，百格往返的 85 秒内有一个完整客户端和 29 个 Forge 协议连接。

首航图解现可逐个点选样船和船坞所需材料，查看当前世界的实际合成配方。手持终端潜行使用可标记飞船和勘察点，再按方位与距离返回。中文主世界客户端和正常停服后的玩家存档已核对，见 `docs/starter-materials-verification.json`、`docs/field-survey-verification.json`。长 200 和 300 格的原货船已分别在一个完整客户端和 29 个 Forge 连接下往返原月球，见 `docs/large-roundtrips-verification.json`。后续重点是从原月球甲板步行勘察、从空背包真正筹建和多舰长期服，先检查正在执行的自建测试进程。

**各阶段的验证记录。** 当前工作分支 `codex/original-spaceflight`。生产星图在两个扩展共存时识别 41 个目的地。两个完整客户端已经从原组装机出发，在不同座位随原火箭升空并在固定船舱取货。另有真实客户端在三种原世界取得首领钥匙并打开原宝箱。双人驾驶与跨层机器操作用过长 300 格、1,844 个方块的预置船舱。核对 `docs/shipyard-verification.json`、`docs/original-worlds-production.json`、`docs/star-map-and-dock-verification.json`、`docs/orbital-controls-verification.json` 及后续报告中更晚的试验。

双人真实原月球 `flight7` 从预置船舱出发，返港船员、货物和两程各 12,000 FE 一致，但全维度刷盘造成 180 秒看门狗报警。不能据此承诺长期专服稳定。`transit/PassageStorage.java` 已改为分批写入两端船体区块并异步等磁盘确认。重跑须同时检查服务端崩溃报告、精确费用、原货物、两名船员和航程账本。`run-gc-pilot-joined*` 检验原火箭、原地牢奖励和返航能否发生在同一存档同一航次。遇到冲突以最新原始证据为准。

**接手顺序。** 先读 AGENTS.md、`docs/integration-plan.md`、`mem:verification` 和 `mem:code-map`，核对 Git 分支及当前未提交改动，再检查后台运行的实例，以免与已有试验重复。安装目录和共存设置见 `docs/install-original.md`。掉线、强制重启、单人及局域网各自已有特定记录。按上方“下一轮验收”选尚无证据的场景。长时间、大船、多舰同时在线要用实际测量值报告。

原组装机的「零件位置」会切到示例船对应甲板并标出原部件。完整生存客户端通过缺导航电脑、按图放回、重扫并发射，双人客户端通过升空途中掉线、固定舱重连和取货。7×6 截面的 100、200、300 格原船已由一名模拟驾驶员验证原组装、加注、原火箭升空、货物与正常停服冷启。300 格最慢交接帧约 2.34 秒。见 docs/shipyard-verification.json 和 docs/loaded-shipyard-verification.json。

原组装机至原月球的双人联飞已在同一存档走通。joined2 从原宝箱把四份火药装船带回，joined4 在两端收据落盘优化后把三份小麦带回，两轮均验证工程师返程断线重登。以 docs/original-crew-verification.json 的 joinedRuns 核对发行包和实际预置。首次从生存材料造船、独立步行探索地牢及长期服容量仍需分开实测。

首航图形入口已有实测。生存玩家得到指南针后能在配方书制作航图终端，得到铁锭可解锁舰桥。终端的「舰桥配方」按服务端配方绘制九宫格，绑定后可在星图的「首次航行」回看。证据见 `docs/first-flight-and-lan.json`。`joined4` 已复核。发布先运行 `verifyProductionJar` 并锁定实际安装的文件，避免开发名称映射造成启动错误。稳定性结论须注明船型、服务器延迟和连接类型。

舰桥界面配对 CC 高级电脑、授权 MCP、手动接管和恢复巡航已实测，见 `docs/computer-flight-verification.json`。`run-connected-fleet-a5` 完成 10、20、30 个真实网络连接的短时三舰巡航，各档 1,200 刻，见 `docs/connected-fleet-verification.json`。进一步的容量结论应来自长期、多维度及物理化交接场景。

2026-11-22，原月球返航扣费落盘、船员尚未登记时强制中断，修复包 `d9cf6b9d` 已通过两种双客户端恢复。`run-gc-pilot-recovered3` 重开旧版失败存档副本，`run-gc-pilot-restart5` 从原组装机重新出发并带回原图纸。两例的同船船员、货物、只收一次航费和清空账本在 100 刻观察中通过。可复用包在 `.research/orbit-dev/original/qa/stable-versions/recovery-v1`，安装哈希与两端记录见 `docs/original-crew-verification.json`。恢复代码已单独提交于 `ee73c28`。同包 `restart6` 去程工程师落到船底，双人在甲板的断言超时。本次没有触发返航中断。

原月球着陆仪现在在转换船体前显示所选地点的实际地形和船体范围，船长可以重选或复飞。两个普通生存客户端已从预置固定舱经原月球实际触地并载货返港，见 `docs/landing-instrument-verification.json` 的 `survey3`。新构建保持着陆仪两端网络版本一致。

30 人原船静泊工作测了 12,000 刻，三艘 100/200/300 格原船，22 台炉产 1,320 铁锭。100 格原船在 30 人时两次落到 Galacticraft 月球，最后一次点返航着陆后 VS 2.4.11 在玩家区块观察状态处使专服崩溃。原船和货物已写入主世界，返航收据仍待提交。见 docs/native-fleet-verification.json 的 physical_under_load。这是 B6 旧包的结果。

在上述 30 人返航崩溃存档的独立副本中，原正式包冷启后船长用真实客户端重登。100 个在线服务端刻后，活着回到主世界的原船，85 颗钻石和 40 份炉内物品一致，12,000 FE 只扣一次且账本清空。见 docs/native-fleet-verification.json 的 cold_recovery。

`restart7` 用与原月球着陆实测相同的正式包 `347c4ef0` 从原组装机双座升空，带回原地牢图纸。在返航已扣费、船员未登记时强制中断，停机期间两端共 8 份区域文件全部核实。两个真实客户端重登后船员、货物、单次扣费和空账本在 100 刻观察中通过。完整证据在 `docs/original-crew-verification.json` 的 `interruptedReturns.restart7`。

30 人原月球返航已用新的专服存档 `watchfix2` 走通。VS 2.4.11 的兼容修复让目的地船体订阅晚一刻建立。完整客户端从月面使用已配对的终端打开返航星图，100 格原船、满血船长、85 颗钻石和 40 件炉内物品返回主世界。两笔各 12,000 FE 有独立收据，停服后的账本为空。其余 29 个 Forge 连接保持在线，200、300 格船本轮静泊。见 `docs/native-fleet-verification.json` 的 `physical_under_load.watch_handoff.regression`。待验的是更大船实际往返和长期负载。

完整扩展组合的双人航程已在 `e1927ec3` 验证，详见 `docs/original-radiation-verification.json` 的 `protected`。玩家在实际可制作的银河空间套装外加高级火箭原气密处理后能在月面活动。`transit/ArrivalDeck.java` 核对船体真实碰撞面并等待乘员站稳。两人带回同一份原宝箱命名牌。

统一包 `e1927ec3` 的 `crewdeck1` 已在 30 个持续在线的 Forge 连接下把 100 格原船从原月球送回。两端独立测量船长脚下的船体碰撞面，船员、两程收据、五处货物和落盘后的空账本都通过。详见 `docs/native-fleet-verification.json` 的 `physical_under_load.watch_handoff.crew_deck_release`。

普通船员从未绑定航图终端的「首次航行」也能点击「打开氧气装备栏」，无需船主绑定。船长可从星图的月面装备页进入。三组双客户端专服已覆盖正常穿戴、未安装 GC 和原容器不可用，见 `docs/original-oxygen-equipment.json`。

`run-concurrent-berths-a2` 已用两个中文生存客户端同刻确认月球泊位，争位被拒的一方直接在图上改选，无损货物。公网 CI 包 `364d5aeb` 四次着陆各收一次费。10–30 连接的独立 48 小时服 `run-concurrent-soak-a2` 从北京时间 2026-11-22 05:13:09 起计时。接手先读 `docs/concurrent-orbits-verification.json`，再查实例内 `observer.json` 和结束时的 `soak-final-audit.json`。保持正在运行的专服，连续时间按相同服务端进程的实际记录计算。
