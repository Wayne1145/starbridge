# Starbridge 项目交接

**本机输入限制（2026-11-22）。** 用户报告鼠标被图形测试周期性抓住。已停止客户端和等位启动器。仅运行无图形任务；私有 Win32 桌面也不能用于此电脑的图形测试。此前 48 小时专服试验依赖 Forge 客户端负载进程，已于北京时间 10:50 主动结束，累计观测约 4 小时 42 分钟，未通过 48 小时验收。后续图形测试需要真正独立的输入环境。

2026-11-22 11:38 上海时间复查时，项目仅有 `nogui` 专服，Windows 计划任务无项目重启器，光标活动范围覆盖整个双屏桌面。本机一直保留 `.tools/no-local-game-clients`。本机只做编译、读档和无界面专服，图形操作用 GitHub 托管 Linux 的 Xvfb。已公开的 `ff4b631` 安装包含返航改选和星图校准，构建 `44981665093` 已通过。远端界面运行 `44981665189` 的三个场景已通过，真实动作、截图和校准后的存档核对见 `docs/remote-menu-verification.json`。

目标是 Forge 1.20.1 联动附属。高级火箭的原发射台、塔架、组装机、推进剂、座椅和实体负责发射。Galacticraft、ExtraPlanets、GalaxySpace 自己提供星球、世界生成、地牢、首领、钥匙和科技图纸。默认星图只登记实际提供方。游戏保持 1.20.1，以 Retrofit 的 1.7.10 运行环境加载锁定的原包。0.2 原型在可选历史资源包和历史文档中。

**玩家界面是一项发行验收条件。** 普通生存玩家关闭作弊，能在对应方块上找到入口，通过可点选界面、方块和可改绑按键完成造船检查、扫描、装配、加注、登船、选星、手动或自动驾驶、选落点、供氧、返航和人工接管。界面提示显示真实绑定、当前状态和解决办法。多人船员能在固定的多层船舱照常活动和用机器。船主在舰桥授予和撤销电脑、AI 的控制权。测试记录区分点击界面的真实客户端、模拟玩家、控制台和预置条件。这条规则也适用于以后新增的生存玩法。例如着陆仪要在玩家确认前用地图和当前语言指出不可降落的区域，并让玩家就地改选。测试时应能从界面看出下一步可做什么，以及操作后发生了什么；只有文字说明或聊天命令的步骤仍待补齐。验收要分清程序在真实客户端点过按钮，以及第一次玩的真人自行找到入口，两种结论各用各的证据。

安装排查从还没有存档时算起。组件检查在 Forge 模组菜单和未绑定的航图终端内，显示本机实际载入情况。联机时由服主另外检查服务端。

首航的入口从还没有本模组物品时算起。普通玩家取得工作台后，在原版「进度」的「星航 · 首次航行」依次查看指南针和航图终端，再用工作台配方书和终端打开造船图。默认使用键和改绑 U 的中文验收见 `docs/first-steps-verification.json`。

备料也用同一标准。图中允许操作的零件应能直接点选，鼠标和键盘都能定位。例如压板器图中的锡块带角标，点开配方后返回仍能看到原来的通电状态，见 `docs/tin-recipe-route-verification.json`。玩家从航图终端点材料图里的物品，能认出每种实际输入，包括药水种类和旧模组物品的不同变种。命令留给服主管理和开发诊断，电脑和 MCP 控制由船主在舰桥上选择。

勘察也按这条规则验收。手持终端，在舰桥点「地表勘察」能标记脚下并回到原先选择的行星，见 `docs/helm-survey-verification.json`。船员在甲板记录船位，选「飞船」并关图后可以边走边看返船指针。收起终端应隐藏指针。月面新近景有独立缩放、逐格高度与坡沿亮线。中文单人生存客户端的准星辨岩、近景实测和停服副本看 `docs/lunar-survey-detail-verification.json`。自然远征工程师从两个坑沿看见洞口，走入后在终端中辨认出天然月岩。较早副本 `run-lunar-local-wide-recon-i` 留四份口粮。最新的 `run-lunar-local-near-wall-j` 于 10:50 正常保存，工程师满血，带三份口粮和原终端。四份专服世界副本已核对竖井和房间能连通。已记录的玩家视线没有确认地牢入口，钥匙及这趟返船仍待确认。冷启动检查见 `qa/lunar-cold-audit/README.md`。接续看 `docs/lunar-natural-search-verification.json` 和 `qa/original-flight/continue_surface.py`。另一个主世界试验台已核对露出的原砖、挡在砖前的月面草皮和空旷视线，显示原模组物品名，见 `docs/lunar-rock-identification-verification.json`。

图形验收从玩家自己找到并打开入口算起。以返航为例，船长在月球用已配对的终端打开星图、选母港并确认着陆。测试服直接推送星图时须记录这个预置。

后台设计以分段保持同一船和一份库存为原则。太空时正常运行的船体留在固定船舱，驾驶员第三人称看见自己搭的船，服务器推进航行坐标。近地交给 Valkyrien Skies，反向起飞恢复固定船体。船长 100、200、300 格要分别记录宽高、实际方块、机器和每阶段耗时。单人、局域网、10 至 30 人长期专服要分别实测。测试使用独立存档。本机只运行无图形任务。

玩家须从游戏里获得做决定所需的信息。起飞补给的三个图分别显示原机供电、液体推进剂和舰桥储能，见 `docs/launch-supplies-verification.json`。船主可在舰桥点选真实已连接的电脑和撤销授权，无需查编号。实际客户端及 MCP 核对见 `docs/device-picker-verification.json`。`docs/integrated-fleet-verification.json` 保留返航卡顿的初测，修复后单人 100、200、300 格窄船返航帧间隔中位数分别为 44、55、41 ms。100 格首轮同机竞争资源时为 88 ms，较少争用时为 44 ms。100 格双人局域网两端均抵达。宽体、多人长期服及不同显卡性能另行验收。

## 接手时先看当前进度

本机图形保护入口在 `tools/local_client_policy.py`、`scripts/local-client-policy.ps1` 和 `gradle/local-client.gradle`。关联工作树继承主仓库的标记，配方和供电诊断直接运行纯专服。十个 QA 入口以及 PowerShell、Gradle 的无图形复核见 `qa/input-policy/verification.json`。

原航线的科技门槛现可从舰桥图形界面解决。`docs/navigation-calibration-verification.json` 和 `qa/navigation-calibration` 记录全扩展无图形专服的物品、权限与存档试验。独立图形环境需用真实原包和 `starbridge-navigation-proof.fixture` 留下玩家打开火星、查看图纸、校准和返回同一星图的证据。

2026-11-22 10:50:17（上海）已应用户请求保存并关闭本机生存客户端、专服及续跑器。本机只做无图形验证，不再打开 Minecraft 客户端或隐藏桌面。`run-survival-expedition-a1/recordings/20261122-104205-3e9f` 的工程师游标为 680。停服证据在 `qa/survival-expedition/survival-tin-verification.json` 的 `gardenCheckpoint`。屋顶两根甘蔗仍在，终端尚未制成。

首航造船图已把俯视和侧视中的部件、键盘可选的材料行接到配方与安装检查。新增输入路径的无图形测试和待补的界面证据见 `docs/shipyard-blueprint-input.json`。

带「去检查 Retrofit 运行桥」的公开包为 `792f80a`，GitHub Actions `44976539965`，本机文件 `build/ci-44976539965/starbridge-0.3.0-dev.zip`。已核对 40 个载荷、35 个安装指南链接、两种语言和 9 份图形界面类文件。 `run-setup-ci44976539965-runtime` 也用公开主包实际点到了尚未启动的旧侧运行桥，并回到原组件。此前 `e350c40` 的公开包 `44974688952` 在实际中文标题画面中确认只缺船坞时显示 7/8。安装检查、双人 300 格手动飞行分别见 `docs/flight-setup-verification.json` 和 `docs/manual-wide-verification.json`。

- 玩法入口和依赖顺序见 `docs/install-original.md`。终端的起飞补给能继续点进两台原机器的实时配方和控制器配方。`docs/bucket-transfer-verification.json` 记录了实际点击四张装桶图及两个原流体口配方。`docs/native-propellant-verification.json` 记录从一桶水到原火箭 1,000 mB 的原机生产与加注。已组装原船的中文仪表见 `docs/packed-dock-verification.json`。
- 完整原包双人联飞及预置条件见 `docs/original-radiation-verification.json`。空背包起步是另一份存档 `run-survival-expedition-a1`，继续前读 `qa/survival-expedition/construction-checkpoint.json` 和该实例最新记录。双人登月见 `docs/lunar-trail-verification.json`。自然寻找入口的较早停服副本是 `run-lunar-local-search-f2`，接续当前进度看本页上方的最近月面副本。该存档只有一名工程师，仍需在坑沿看到原地牢砖，再沿可见通道战斗、开箱、载物返港。已核对的出发、返船和搜索段在 `docs/lunar-surface-walk-verification.json`。
- 大船验收分开读 `docs/large-roundtrips-verification.json` 的专服往返、`docs/integrated-fleet-verification.json` 的单人帧时间，以及 `docs/concurrent-orbits-verification.json` 的多船、长服测试。`run-concurrent-soak-a3` 已应要求终止，停服报告记录约 4 小时 42 分钟，详见 `docs/concurrent-orbits-verification.json`。
- 公开仓库使用草稿 PR #2 的 `codex/original-spaceflight` 分支，受保护的 `main` 要求 `build` 通过。发布给玩家的开发包可从对应提交的 GitHub Actions 下载；先核对压缩包内的 `SHA256SUMS.txt`、两份自产 JAR、图文说明和原始测试所用构建。

## 过往构建的验收沿革

以下条目记录当时的结论和待办。遇到后来的复测，以上面的分类记录、运行编号和原始数据为准。

**早期 0.3 开发包联飞（2026-11-22）。** `0.3.0-dev` 主包 `e1927ec3` 的全扩展双人登月、个人氧气装备栏三个场景和 30 连接下的 100 格原船往返均通过。实际输入、测试预置和延迟见 `docs/original-radiation-verification.json`、`docs/original-oxygen-equipment.json`、`docs/native-fleet-verification.json`。单人关作弊用配方书合成、内网加入和电脑接管另有单独场景。下一轮从新档自行收集材料、照首航图建船、探索地牢并返港，再验证 200 和 300 格船实际往返。多舰靠泊与长期连接的进展见本页最后的 `run-concurrent` 记录。10 至 30 人是长期目标，百格往返的 85 秒内有一个完整客户端和 29 个 Forge 协议连接。

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

`run-concurrent-berths-a2` 已用两个中文生存客户端同刻确认月球泊位，争位被拒的一方直接在图上改选，无损货物。公开 CI 包 `364d5aeb` 四次着陆各收一次费。`run-concurrent-soak-a2` 于北京时间 2026-11-22 05:51:56 因磁盘剩余量低于保护阈值而正常保存停机，10 个独立连接累计 38 分 1 秒。20 和 30 人未开始。长期服使用主世界固定舱与 AR、GC 基础组合。详细数据和终审见 `docs/concurrent-orbits-verification.json`。新的 48 小时验收另开存档，从零计时。

着陆仪的大图用橙色叉标出不可降落处，绿色空心准星标出净空泊位，等候测绘时画三个点。每次验证占用与重选都分别核对服务端数据、客户端按钮、已经渲染的截图；相关原始记录在 `docs/concurrent-orbits-verification.json`。

全扩展持续服 `run-concurrent-soak-a3` 用经 200/300 格原船返航实测的主包 `5c0329c3`。2026-11-22 06:08:07 北京时间，服务端刻 4742，十个本地 TCP 游戏身份均在舱内移动，三船巡航，48 小时计时重新开始。查询 `qa/concurrent-orbits/check_soak.py`、实例内 `observer.json`、`soak-final-audit.json` 及 `docs/concurrent-orbits-verification.json`。另一个全扩展专服 `run-concurrent-berths-full3` 已通过两个上游世界首访，两位船长的原船和各自库存均已在停服存档核对。

车间界面已从中文生存终端点通，原版配方书取压板器的独立单人实测有预置原料。实际安装的主包和已落盘背包见 `docs/workshop-verification.json`。双人从零首航另用 `run-survival-expedition-a1`，原混凝土、座椅和活塞的记录在 `qa/survival-expedition/`。继续看当前存档的停服记录与每个角色的操作游标。

月面从船位出发、从休息点步行回到同一艘原船的单人复测已通过。继续自然寻找地牢和核验多人同行时，可接续 `docs/lunar-surface-walk-verification.json`。这里的返船只到月球甲板，地牢和带着该次发现返港仍需实测。

空背包双人首航同档已建好地表营地。船长停服时在屋内，室外箱有木料、白羊毛和食物。工程师从营地接续做照明、床、采矿和原加工设备。接手核对两人的 `expedition-cursor.txt` 及 `qa/survival-expedition/surface-camp-verification.json`。

车间配方已补上原压板器的可切换摆法图。一个锡块消耗后，实际服务端和落盘玩家背包均为四片原锡板。中文实机和入口见 `docs/press-operation-verification.json`。

同档自建现已做到原小型压板器。工程师另开西侧阶梯从 Y=64 下达 Y=12，采红石后返回工作台。压板器和七份红石位于室内箱 `(-89,64,-18)`。原始存档证据在 `qa/survival-expedition/survival-press-verification.json`，接下来用原锡料、黑曜石和拉杆实作锡板、电池和发电机。

原机整桶推进剂已从 `run-native-fuel-factory2` 和其停服副本 `run-native-fuel-recovered-a` 走通。玩家从水桶做出氧气、氢气和推进剂，点击船坞加注后，原火箭的停服实体有 1,000 / 4,500 mB，玩家有四个空桶。机器、燃料和未组装的样船由测试预置。看 `docs/native-propellant-verification.json`。中文首次航行的四张装桶图及两种原口配方已实测，见 `docs/bucket-transfer-verification.json`。

同档工程师已经步行找回铁镐和盾牌，并存入室外箱。两张营地床与绑定的重生点均已在停服存档核实。续档先看游标，狩猎后记得从室外箱取回工具再下矿。见 `qa/survival-expedition/camp-recovery-verification.json`。

组装完成后的原火箭由船坞「发射准备」读取真实仪表。旧存档重开已验证，见 `docs/packed-dock-verification.json`。

空背包双人档的木屋里已有原压板器、拉杆和船长用九个自炼 Galacticraft 锡锭做的 LibVulpes 锡块。原工作台手摆九格已通过。同档 09:33 已保存并退出，记录 `20261122-093126-cb72`。船长在地表寻找可见的黑曜石或岩浆，尚未确认地点。其携带的是水桶，遇岩浆时可就地浇筑。工程师在下层矿道，船长和工程师的操作游标分别为 295、591。续档用原角色及固定测试附属 `372cb394`，详情见 `qa/survival-expedition/survival-tin-verification.json` 的 `surfaceScouting`。随后工程师返营取食，自制并装备盾牌，满血满饥饿保存，游标 608，见同一报告的 `engineerReturn`。10:15 工程师单客户端 `20261122-095151-9729` 已正常保存。营地箱有自制指南针和 16 块玻璃板，工程师身上有两根甘蔗；终端待合成。游标 654，下一步返营栽培甘蔗做纸，见 `slateMaterials`。随后工程师在 `20261122-101702-f077` 徒步捡回掉落，亲手在屋顶种下两根甘蔗。停服后完整备份核对了 663 个世界文件，两个客户端及服务器现用公开主包 `b2c0cc5b`，测试附属 `7900a0e2`。`readslatecheck` 通过玩家的使用键打开自制终端，点安装检查、返回，再切换造船俯视和侧视。10:36 的 `20261122-103348-d835` 正常保存，游标 680，角色在作物旁，等待甘蔗自然生长。后续客户端续档在独立远端环境运行。`build/survival-render-slot.json` 只用来查历史，本机等位启动器已停用。

宽 19、高 8 的双层原船已按 100、200、300 格的船长完成双人局域网原月球载货往返。300 格船曾在保存阶段使两人摔落，修复后在同一工况带回两人及全部货物。看 `docs/wide-fleet-verification.json` 与 `mem:wide-fleet`。制油两台原机器的逐层图已与实际装配的 19 格对照，证据看 `docs/machine-layout-verification.json`。

公开 CI `44969267385` 的 `a90ea7c` 安装包已核对 32 个文件和 27 个随包链接。`run-native-fuel-player-assembly-c` 中，普通玩家用散件摆好了原电解机和化学反应器，两个原控制器的完整结构状态均已保存。看 `docs/player-machine-assembly.json`。原版模组和扩展仍按清单安装。

主菜单「模组 → 星航 → 配置」能检查本机组件并找到锁定文件。见 `docs/flight-setup-verification.json`。300 格双层原船的双人手动往返和复飞已经用两份存档核验，见 `docs/manual-wide-verification.json`。

30 连接宽体专服的首次试验 `run-wide-load-original300-a` 因船长使用服务端旧侧缓存而无法握手。`run-wide-load-original300-b` 换用各端自己的文件后，船长和前四个负载连接已进入，随后第五个连接超时。两次都已停服保存。`run-wide-load-original300-d` 已用公开包 `a90ea7c` 和 300×19×8 原船完成原月球往返。30 个连接中有 1 个渲染船长和 29 个分别位于两艘停泊船的负载连接。就绪后持续 152.29 秒，19.71 TPS，停服货物审计 21 项通过。看 `qa/connected-fleet/wide300-verification.json` 和实例内 `server/large-flight-audit.json`。该轮 48 小时测试已在约 4 小时 42 分钟处停止。

返航着陆图可在母港周围改选同高度的泊位。`qa/home-berths` 在零客户端专服验证原泊位阻挡、备选位置和真实 VS 货船跨维落盘，见 `docs/alternate-home-berth-verification.json`。远程运行 `44982848366` 中，程序操作的中文生存船长实际选回中心，再改选附近泊位，第一次确认遇障，第二次载货返航。停服存档有七颗钻石、一次 12,000 FE 扣费，见 `docs/remote-home-port-verification.json`。

月面自然搜索新增了地形剖面。从勘察图点选地形，先比较东西与南北两条 25 格轮廓，再到坑沿辨岩。托管 `Lunar field survey` 重新生成旧远征种子的月面，以旧终端标记的船位为出发点。独立场景与预置见 `qa/lunar-field/README.md`。托管运行 `44991338272` 拍到三类剖面和天然月石。首轮角色最后 25 个样点停在同一平面位置，没有触及原地牢砖。下一轮读取图上步级安排绕行，抵达实际坑沿才观察。记录 `docs/hosted-lunar-field-verification.json`。

托管运行 `44988165497` 已从预置两株甘蔗和备料的七个数值走通自然生长、收割、制纸、制作与使用终端、入箱和关服验存档。原空背包双人档仍在游标 680。见 `docs/survival-terminal-verification.json`。

原组装机现在能标记发射台地面的真实缺口。托管生存玩家已经补块、重开并看到扫描可用。100、200、300 格原平台的专服验证及旋转后的逐像素核对在 `docs/launchpad-floor-verification.json`。300 格原船的一次离港快照检查了 19,522 个方块和 4,530 个机器数据，恢复边界与计时见 `docs/hull-capture-verification.json`。

三船十连接的短测已在独立 GitHub Ubuntu 测试机通过。十名船员的停服位置与货物见 `docs/linux-fleet-verification.json`。连续 48 小时仍需独立远端环境。

2026-11-22 13:57 复查，本机只有 Gradle 构建进程，未发现项目客户端、Windows 等位重启任务。7 秒内只读查询 15 次，光标范围每次都覆盖整个双屏。持续保留本机禁用标记，界面验收交给远端。

双人空背包远征已迁到 `qa/survival-expedition/hosted-pair`。两名程序操作的玩家各用一个 GitHub 托管客户端连接同一专服，首段验收各自的工作台、木镐和圆石，后续从同档收集原造船材料。远端首段仍待实际结果。长船禁用方块的修补见 `docs/shipyard-forbidden-blocks.json`。

正式三舰长测的 Linux 入口是 `qa/linux-fleet/long_soak.py`。在有授权的独立测试机积满十人两小时、二十人六小时、三十人四十小时；截至 2026-11-22 尚待实际运行。启动和验收见 `qa/linux-fleet/README.md`。

双人空背包的远端存档 `44992119186` 已完成工作台阶段。船长和工程师游标各为 10，PR 中的材料段从同一份 GitHub 原始世界接续。见 `docs/hosted-survival-pair-verification.json`。
