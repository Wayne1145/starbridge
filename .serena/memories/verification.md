# 实测与待验证项目

`45602088144` 的完整原包与 JEI 15.75.0.239 已在 Forge 47.4.28 中文创造客户端验证实际物品列表。用户已安装的 `v0.3.0-pre.1` 复制到独立纯专服也已确认全部原包和月球区块。`45628476393` 实测图形界面将下载设置从 `never` 恢复到 `ask`。预发行 `v0.3.0-pre.2` 在同一纯专服也已核对三个原版世界落盘区块。[原物品栏与 JEI](../../docs/original-inventory-verification.json)

2026-11-26 的用户 PCL 实例只读复核通过包校验、Forge 库校验和 Retrofit 客户端缺件检查。日志仍停在修复前。下一份用户日志用来确认 Forge 47.4.28、Java 17 与原包实际载入。[本机检查](../../docs/original-inventory-verification.json)

`45575288297` 的中文生存客户端已验证下界疣来源、打火石配方和讲台配方。`45570141661` 的同档黑曜石备料到达北岸后阵亡，关服保存，`45576943202` 已留住原钻石镐和打火石，仍停在水中站位前。`45611735926` 后来拾取前两块，第三块留在关服矿坑。各段原始证据见 `docs/test-report.md`。

`45557137256` 已重放十连接两小时完整窗口和客户端计数。托管关服审计通过。实际构建是 `912daef`，使用一个渲染进程。20 人和 30 人的长测仍待进行。见 `docs/linux-fleet-verification.json` 的 `repeatTwoHourDiagnostic`。

查看某个版本是否支持某项功能时，先读 docs/test-report.md、docs/expedition-verification.json 和 docs/verification-results.json，再对照测试场景源码。下述数据属于不同运行。Forge 或物理依赖升级之后，需要重新采样。

0.3 实测先看 `docs/original-radiation-verification.json` 的原模组航程、`docs/propellant-guide-verification.json` 和 `docs/device-picker-verification.json` 的玩家入口、`docs/integrated-fleet-verification.json` 的驾驶帧时间及 `docs/concurrent-orbits-verification.json` 的长服进度。下面的历史顺序按各自构建解读。

在真实月面从两处坑沿看见洞口、步行进入并辨认天然月岩的存档和证据见 `docs/lunar-natural-search-verification.json`。该段仍待自然确认首领房和钥匙。 `45033335545` 的关服档及爆炸前地面见 `docs/continued-lunar-field-verification.json` 的 `collapsedCorridor`。 `continuedOnOriginalFloor` 另存 `45034891414` 正常关服后的同层探路结果。 `carriedOriginalKeys` 记录 `45035718844` 同角色背包与拾取统计里的两把原钥匙，首领击杀归属仍缺。 `raisedBrickPause` 记录 `45037594991` 重登后站在高一格的原砖上，背包两把钥匙保持。

勘察界面的准星辨岩、近景逐格高度、陡坎和缓坡的实际截图取样见 `docs/lunar-survey-detail-verification.json`。`optics-e` 的测试记录器异常后各维度已保存，`zoom-f` 在 Windows 文件占用期间继续行走并恢复记录。`relief-g` 使用新图正常存盘。后续 `transect-h` 已保存，仍待从地表发现原地牢。预置试验台的露砖、岩面遮挡、天空以及原方块名见 `docs/lunar-rock-identification-verification.json`。

2026-11-21 的 Forge 47.4.28 普通专服场景 a5 使用 UnBound 0.16.1、VS 2.4.11 和两套完整 Minecraft 客户端。同一艘 52 方块的小船从主世界到 unbound:moon 再返回。两名在线生存船员抵达两处甲板，船号与核心保持一致。两程各扣 12,000 FE，17 颗钻石与炉内输入加产出 8 件均保留。服务端在月球停泊时往箱子放了 5 颗紫水晶，返航后检查了该格数量。两人的上游科技前置与船长的月球观测是预置数据。服务端自动发起航程。运行期间 VS 有找不到旧船物理帧的警告，服务器报告一次落后 44 tick，原因待测。证据在 docs/expedition-verification.json，记录了原始结果、三个进程日志和断言文件的位置。

同版合装 Forge GameTest 的 14 项框架成功中有 13 项实际执行了检查。原生飞行诊断在该次没有观察者时按配置跳过。两项 UnBound 联动验证使用模拟船员，分别覆盖日志与静止舱室的供氧、耗氧和失压。Java 单测、MCP 桥测试及 GitHub build 可以验证代码或打包，均不能证明多人长期服容量。

早期 Forge 47.4.10 的独立服加一名观察者实测中，100 格长、14,709 方块、24 台机器的船通过了 8 格短航点和悬停。300 格、124,507 方块的船在装配时约停顿 11.9 秒，随后航点飞行超时，无人观察时的推力也未确认。当前仍缺正常生存开局操作望远镜、推力下多层甲板交互、断线和重启的恢复、局域网、200/300 格船正常飞行、10–30 人长期服的数据。重新测量时保留船体实际方块和机器数、观察者人数、时间、维度及失败阶段。

构建用 JDK 17。标准命令和完整实机场景见 CONTRIBUTING.md。待办排列见 mem:start-here，断言与运行时入口见 mem:code-map。更新结论时先更新版本化报告，再将证据和具体适用范围写回本条。

扣费恢复修正后，普通专服同一双客户端场景 a6 再次通过。细节在 docs/expedition-verification.json 的 post_fix_repeat。磁盘回归覆盖扣费后但航程日志尚未提交的中断窗口，独立服强制停机复测仍待进行。

一组使用 Forge 47.2.18、Java 21.0.1 的客户端启动测试发现，独立 Rubidium 与 Embeddium 会重复声明 `rubidium`。禁用独立 Rubidium 后，带 Starbridge、Embeddium 和 Oculus 的 15 个 JAR 到达游戏主菜单。该测试只验证客户端启动，证据见 docs/compatibility.md。
同一组合的独立副本加入 Valkyrien Skies 2.4.11 与 Kotlin for Forge 4.11.0 后，17 个顶层模组 JAR 也进入了主菜单。该整合包的世界内飞行尚待验证。

2026-11-22 原装配机独立专服有新的后端对照结果。宿主 Forge 47.4.28 + Retrofit 0.6.2-beta、AR 1.4.0-32、LibVulpes 0.2.8-37，普通船体能生成原火箭，同布局带现代箱子与熔炉则在复制方块实体时停摆，货物留在原地。无客户端，控制台放块并恢复原机待处理任务，没有点按钮、乘坐、发射或跨维。精确断言与原始日志在 docs/shipyard-verification.json，说明在 docs/test-report.md。

2026-11-22 已用两套完整客户端在非管理员生存专服验证太空驾驶视角、改绑按键、着陆仪、离席返回，以及航行中上楼打开箱子和熔炉。a1 预置小船和航程。b1 使用实际长 300 格、宽 6 格的固定船体，前后两台熔炉均有产物。原包组装、真实着陆、宽体母舰、原地牢和长期多人规模仍待单独验收。实测断言、输入记录、日志与截图见 docs/orbital-controls-verification.json。

原组装故障已在 0.3.0-dev 的混合货船验证里修复。见 docs/shipyard-verification.json 的 bridge_fix。真实专服加一名 Forge FakePlayer 从原机按钮处理走到原火箭实际升空、固定船舱，船员和货物通过核对。未验证真实界面点击与正常加注。

2026-11-22，同种子两份 1.20.1 专服独立存档验证了地形种子适配。原月球地牢、带首领类的刷怪器及上锁的一级原箱已经自然生成，两扩展各有独立地形样本。配置、坐标、原始证据、合装加载列表和适用范围见 docs/original-runtime-verification.json。

0.3.0-dev 的 a4 独立服加一个真实客户端已验证未绑定终端的两页图解，以及原月球进化骷髅、上游一级钥匙掉落与拾取、原宝箱界面和两颗入包的铁锭。测试预置装备、药效、房内和箱边位置。没有航行或双人首领验证。见 docs/original-runtime-verification.json 的 encounter。

0.3.0-dev 原组装机另有真实单客户端与双客户端专服图形记录。双人预置船上通过了原加注、双座实船升空、分别入舱、工程师走动和箱内外钻石守恒。见 docs/shipyard-verification.json 的 real_client_ui、real_two_client_ui 和 docs/shipyard-fixture.md。证据止于固定舱。

正式构建含旧存档种子保留与跨区块原首领方块实体修复。run-production-encounter-a2、a3、a4 对应月球、谷神星、金星，三个独立原世界自然生成、原 Boss、拾原钥匙和玩家开原宝箱各通过。生成及玩家实机的代码、原始结果、已知遗漏见 docs/original-worlds-production.json。

原扩展图纸也已走通 NASA 工作台原界面。run-production-blueprint-a1/a2 用谷神星、金星原箱中取得的物品使原玩家分别新增四级火箭及金星漫游车页。见 docs/original-worlds-production.json 的 schematicFollowups。

0.3.0-dev 原月球双客户端用供氧界面补气，已测关舱、真空伤害、开舱和复压。预置舱的一次原船载货返航有双人、库存和两笔收费凭据，但触发了专服看门狗，不能当作健康往返。实际截图、哈希、断言与场景限制见 docs/original-crew-verification.json。

升空中乘客掉线并在固定舱重连已通过双真实客户端专服验收，见 docs/shipyard-verification.json 的 real_two_client_reconnect。7 格宽、6 格高的 100、200、300 格双层原船经一名模拟驾驶员完整扫描、载货原升空、入舱和正常停服冷启。300 格交接仍有超过两秒的单帧，详见 docs/loaded-shipyard-verification.json。

原船联飞 joined2 在同一场双客户端任务中走通原组装机、双座发射、原月球首领与钥匙、原箱到船舱的四份火药、返途掉线重连和两笔收费。测试预置船体、装备及三个地牢和船舱位置。有短时卡顿，未出现看门狗崩溃。同哈希三端复测 joined4 带回三份小麦，未出现看门狗报警，结果包括真实重连和两端收费。详见 docs/original-crew-verification.json。

原船缺少导航电脑时，普通生存玩家用「零件位置」查看示例、放回物品并重扫发射，已用完整客户端核对。见 docs/shipyard-verification.json 的 real_client_repair 与 docs/images/shipyard/07-missing-guidance.png。

真实 Lua + MCP：run-bridge-controls-a2 中非管理员船主在舰桥 GUI 配对高级电脑并授权，本机 MCP 与游戏内 Lua 实际控制原月球巡航，手动驾驶时双方均被拒绝。点回巡航后控制恢复。见 docs/computer-flight-verification.json。

太空三舰短测 run-connected-fleet-a5 通过。一个 Forge 客户端进程建立 10／20／30 个独立游戏连接，各档 1,200 刻，三船巡航期间每个角色均有移动、熔炉有产出。三者共用一台测试主机。30 连接约 20 TPS，p95 27.16 ms、峰值 51.70 ms。短时固定船舱测试未测长期运行、图形帧率或物理化着陆。安装包哈希、两端记录及复现见 docs/connected-fleet-verification.json。

在 run-habitat-first-craft-b 的关闭作弊单人新档，客户端用配方书条目在真实工作台合成航图终端和舰桥，看到同步的九宫格，放置、绑定并从舰桥重新打开图解。独立的 run-habitat-fifth 验证了供氧、收到 33 刻的手动输入及局域网第二客户端加入。前一轮在后续预置 VS 甲板上有人落到地面，不能把两项记为一次全程成功。passage8 在默认 60 秒看门狗下完成预置船的双人载货往返。见 docs/first-flight-and-lan.json。

原船 10、20、30 名玩家的普通 TCP 联机已分档验收。三艘 100、200、300 格原船均有存档身份与工作设备，其中一名玩家运行完整游戏画面。30 人静泊第 12,000 刻仍保持全员在线，共测十分钟，22 台炉产出 1,320 个铁锭。容量条件、尖峰及原始日志见 docs/native-fleet-verification.json。

run-habitat-altimeter-a 另用同一新存档完成原材料首航、供氧、近地手动下降至实体平台与局域网。小船从 2 格离地开始，连续 12 刻实测接地，船长仍在甲板。场景提前载入甲板、平整平台并设置初始登船位置。详见 docs/first-flight-and-lan.json。

原月球 `restart3` 在收费已落盘、船员尚未登记时停服，旧包重开后一直有一笔挂起。`recovered3` 在旧失败档副本重开、`restart5` 在修复包新航程中强制中断并重开，均通过双客户端原船、货物、单次收费和清空账本检查，每 10 刻取样至 100 刻。`restart5` 停服期间留存了两端各 3/4 个区域文件，离线全船印记检查仍不完整。两次实测的安装包、进程、原始日志和预置见 `docs/original-crew-verification.json` 的 `interruptedReturns`。

原月球着陆仪双客户端 `survey2` 在边界外及泊位突发占用时保留原舱，复飞后改选安全点。测绘和靠泊从实际方块碰撞体读取高度。33 方块、双人小船已在天然月面测得零离地间距。最终 `survey3` 的三端同包 `347c4ef0` 把两人、11 颗钻石和 6 份炉料带回，去程及返程各扣 12,000 FE，结清账本并正常保存。先前试验及精确范围见 `docs/landing-instrument-verification.json`。

30 人、100 格载货船的原月球着陆有真实船长与 VS 物理证据。回主世界时 VS 玩家区块跟踪抛出异常，返航未提交。独立读取两端存档已核对全部货物。原始崩溃、账本和后续恢复情况见 docs/native-fleet-verification.json 的 physical_under_load。

30 人返航崩溃的独立复制存档经冷启动后，一名真实船长重新上船、货物保留、在途收据结清，docs/native-fleet-verification.json 的 cold_recovery 保存原始报告。

`run-habitat-blueprint-a` 在关闭作弊的中文单人新档中用原材料实作终端和舰桥。真实客户端切换了船坞俯视和侧视、查看配方、放置并绑定舰桥。`docs/first-flight-and-lan.json` 收录图片和原始动作记录。本次没有按图搭建平台。

`restart7` 的正式包为 `347c4ef0`。两端 4 对区块在专服进程切换之间全部封存并独立核对交易号、扣费和货物。重开后两名生存客户端重登同一艘船，原图纸与 16 颗钻石仍在原格，返航只扣 12,000 FE。`restart6` 在旧 `d9cf6b9d` 包的去程未能确认工程师站在甲板，未触发返航中断。见 `docs/original-crew-verification.json` 的 `interruptedReturns`。

`watchfix2` 已通过 30 个连接、100 格原船原月球往返。一个完整客户端从已配对的月面终端进入图形返航流程，29 个连接提供多人负载。三端同包 `51cd4205`。持久化账本、货物、两次收费、崩溃目录和三次 VS 组装超时均已复核。此前只观察的 `watchtrace1` 重现旧包崩溃，首次候选的 `watchfix1` 止于测试附属名称映射错误。版本、条件和原始证据都在 `docs/native-fleet-verification.json` 的 `physical_under_load.watch_handoff`。

原包全扩展联飞 `releasecrew5` 已用同哈希 `e1927ec3` 专服和两套普通生存客户端走通原火箭、原月球太阳辐射防护、原地牢、命名牌装船、返程重连和回港。`releasecrew2` 单用高级火箭压力服时监测到银河空间的原 `solar` 伤害与八次宿主扣血逐次一致。两次正常防护航程读的是原熔合机、化学反应器的已登记装备。`releasecrew4` 的失败存档里工程师落在月岩，脚在自家箱子顶以下 0.875 格。`releasecrew5` 在同点确认工程师的脚为 Y=64.875，两个玩家都在船上。详细装备、战利品、预置、日志及服务端积压见 `docs/original-radiation-verification.json`。

统一包 `e1927ec3` 的 `crewdeck1` 已在 30 个持续在线的 Forge 连接下把 100 格原船从原月球送回。两端独立测量船长脚下的船体碰撞面，船员、两程收据、五处货物和落盘后的空账本都通过。详见 `docs/native-fleet-verification.json` 的 `physical_under_load.watch_handoff.crew_deck_release`。

`e1927ec3` 的氧气装备入口通过三组双客户端无 OP 生存场景。船长从月面装备页、未绑定终端的船员从首航图解直接穿戴自己的原 GC 四件装备。未安装 GC 或原容器拒绝打开时，两页保留、中文错误可见、物品不变。见 `docs/original-oxygen-equipment.json`。

公开安装包单独验证于 GitHub Actions `44947273292`，主包 `364d5aeb`、ZIP `4627fb5c`。两套图形客户端在全扩展专服完成原组装机、月球地牢、装货、返程重连与回港。实测条件和证据在 `docs/original-radiation-verification.json` 的 `publicBuildRepeat`。

`run-habitat-materials-c` 的中文单人客户端从自己合成的终端看到原发射台、塔架与钢棒的工作台配方，并回到同一张造船图。首航资源由用例提供，详见 `docs/starter-materials-verification.json`。

`run-habitat-field-b` 的主世界普通生存角色用首次航行页和潜行使用两种入口操作勘察图，保存船位与勘察点并步行返回。已从正常退出后的玩家存档核对终端的两枚标记。范围见 `docs/field-survey-verification.json`。

两名普通生存船长在同一刻申请 Galacticraft 月球同一泊位与不同泊位的两轮实测已通过。公开主包 `364d5aeb` 上，中文着陆仪显示占用并停用确认，被拒船主改选后也抵达。同包三舰专服因剩余磁盘触及保护线而中断，十人实际连续 38 分 1 秒，48 小时验收未通过。冻结的原始窗口、两端截图与日志、局限和已知超时见 `docs/concurrent-orbits-verification.json`。完整时长以该实例最终审计为准。

`large200c` 和 `large300b` 分别验证长 200 和 300 格的 7×6 原船载货往返。每整服各一名完整客户端驾驶员、其余 29 个 Forge 连接。月球及主世界脚底与船面间距为零，两程收据和实际存档货物一致。详见 `docs/large-roundtrips-verification.json`。

两台中文客户端在 `run-concurrent-berths-full3` 重复验证原月球的争位与改选，然后分别落在首次载入的 ExtraPlanets 金星和 GalaxySpace 谷神星。两颗原星同时有人 200 刻，停服后复核各船货物、收据、满血船员及空账本均通过。新版大图的占用叉和净空准星已用实际渲染帧核对。见 `docs/concurrent-orbits-verification.json`。

`docs/launch-supplies-verification.json` 核对了原发电机、原组装机读数、舰桥充能与空桶。`docs/walking-bearing-verification.json` 核对主世界转身和收起终端的实际像素。`docs/integrated-fleet-verification.json` 给出单人 100、200、300 格窄船去返程的客户端帧间隔。初测的返航视角卡顿使大船游玩手感仍需修正。

`run-habitat-workshop-craft-a` 的中文单人客户端从自己做的终端查询原车间配方，又在工作台搜索「小型压板器」并取件。实际安装的主包为 `115c1508`，正常保存后物品栏第 9 槽有一个高级火箭原压板器。活塞及三个铁锭由该独立用例提供。参见 `docs/workshop-verification.json` 和 `qa/survival-expedition/press-layout-verification.json`。

`run-bridge-controls-devices3` 以一个真实 CC:Tweaked 电脑和一个中文非 OP 客户端完成点选、解除、再次配对、MCP 授权与人工接管。已关闭的舰桥在刷新回复后 30 客户端刻内保持关闭。见 `docs/device-picker-verification.json`。

双人专服登月后，单人从原存档副本徒步探索、从月面休息点循图回到同一艘原船，已核实原座椅实际支撑、两枚标记与圆石消耗。工程师仍未在这趟徒步中找到原地牢。见 `docs/lunar-surface-walk-verification.json` 与 `qa/original-flight/audit_lunar_walk.py`。

`run-survival-expedition-a1` 的 `20261122-064026-4dfd` 核实同档船长自行搭完储料屋。55 块木板、木门、两只箱子和室内工作台已经从停服区块复核。原始文件哈希见 `qa/survival-expedition/surface-camp-verification.json`。

`docs/propellant-guide-verification.json` 核对完整原包中两台机器的流体批量、两份实际工作台配方及返回加注页。验证用准备好的工作台与材料，真实生产整桶推进剂需要单独验收。

普通烤土豆在装有 GalaxySpace 的露天月面触发需要氧气提示，Galacticraft 原脱水马铃薯罐头实测可食用。已从舰桥星图点开本服配方、查看背包份数并返回。见 `docs/lunar-rations-verification.json`。

`run-habitat-workshop-tin-diagram-a` 的中文单人客户端点击通电预览并操作原机，存档核对一个锡块消耗，玩家背包有四片原锡板，底座和压板器仍在。预置原料和完整记录见 `docs/press-operation-verification.json`。

同档工程师已经用自采材料做成原压板器，确认存于船长营地屋内箱。`20261122-071453-4909` 的存档核实 52 级阶梯和九支火把，见 `qa/survival-expedition/survival-press-verification.json`。

舰桥直接进入勘察图的单人实测、主世界停服存档和截图在 `docs/helm-survey-verification.json`。原月球后续步行搜索从 `search-f1` 到 `search-f2` 正常存盘，最后仍无可见地牢砖。段落、截图、测试包和输入日志的校验值在 `docs/lunar-surface-walk-verification.json`。

`docs/bucket-transfer-verification.json` 记录中文客户端点开四张图和两种原口配方。`docs/native-propellant-verification.json` 记录完整原包非 OP 玩家产出一桶推进剂并在船坞加注。`qa/native-fuel/audit.py` 可复核两次运行的原始输入、两份存档和原火箭的 `fuelLiquid=1000`。

首航入口已用取得工作台时尚无指南针的中文客户端验收。自制终端后悬停进度图标，提示了改绑后的 U，按 U 打开终端并切换造船图。原料由测试准备。存档与运行原件见 `docs/first-steps-verification.json`。同档双人生存的床位与工程师取回并存入铁镐、盾牌的记录见 `qa/survival-expedition/camp-recovery-verification.json`。

`docs/packed-dock-verification.json` 对照同一原实体的停服记录和重开后的中文发射准备，实测油量 1,000 / 4,500 mB、推力 30、自重 18，并从结构示例点回。

`qa/survival-expedition/survival-tin-verification.json` 核对同档双人采料、室内熔炉、手工九格合成和停服储物箱。选原锡块配方时九个锭仍在背包，手摆九格得到正确的原锡块。

`docs/machine-layout-verification.json` 核实中文客户端打开的电解机、反应器两层共 19 格，与正在生产推进剂的原机存档一致。原投影仪的配方也能从此页打开。两台机器自备材料搭建及投影仪在世界中的操作需另验。

`docs/tin-recipe-route-verification.json` 中 `directSchematicInput` 核对真实中文客户端点锡块图标、通电后点配方按钮，两次均看到九格，返回保持原状态。相应的九锭取件在同一双人首航存档独立验证。`qa/survival-expedition/survival-tin-verification.json` 的 `slateMaterials` 核对工程师自制的指南针、16 块玻璃板和仍缺一根甘蔗的制纸进度。

`docs/player-machine-assembly.json` 核对公开版两台原机的普通玩家摆放。预置散件、地面和可到达站位。客户端在可见游戏画面操作 21 次，十九个部件消耗后背包为空，两个原控制器落盘时均已形成完整结构。

主菜单的安装检查有只装主包、完整组合和单缺船坞三种实机记录。另一个关作弊生存角色在缺原模组时用自制终端开图，从两个入口检查并返回造船图。见 `docs/flight-setup-verification.json`。

首航造船图的两项无图形输入测试见 `docs/shipyard-blueprint-input.json`。两种视图的部件、图外和空格命中可离线复核；新增交互未做客户端复测。同档生存 `10:50:17` 的正常停服证据在 `qa/survival-expedition/survival-tin-verification.json` 的 `gardenCheckpoint`。

舰桥读取原图纸的专服实测和停服区块归档在 `docs/navigation-calibration-verification.json`。两名 Forge 模拟生存玩家分属不同舰桥，全扩展真实注册表有 21 种可选凭证，重启后保留星图等级。GitHub 托管测试机上的中文生存客户端已使用原二级图纸校准火星。停服后读取了物品原槽和科技等级，输入、存档与截图见 `docs/remote-menu-verification.json`。

工作区图形启动保护的离线回归在 `qa/input-policy/verification.json`。测试用进程替身验证纯专服分流，用无 Minecraft 依赖的 Gradle 项目验证直接和间接客户端任务，并覆盖关联工作树。

云端运行 `44982848366` 在已配对的月面船上经真实按键与着陆图完成返航，确认阶段分别勘测到占用、净空，停服后核对船、收费与货物。见 `docs/remote-home-port-verification.json` 与 `qa/home-berths/replay_remote.py`。起始甲板为预置，抵达时检查船长在船上，画面与存档摘要归档。

托管运行 `44988165497` 已从预置两株甘蔗和备料的七个数值走通自然生长、收割、制纸、制作与使用终端、入箱和关服验存档。原空背包双人档已在 `45033038360` 做出航图终端，游标 299/700。见 `docs/hosted-survival-yard-verification.json` 的 `originalCampTerminal`。`originalCampYardAttempt` 的 `45036721941` 到 309/712，原座椅确有合成事件，两人死亡后空背包保存，原发射台和原座椅均未放置。

云端 `44989438528` 的三张船坞截图与停服区块经 `qa/remote-ui/replay_dock_floor.py` 复核。旋转后图上缺口从第 3 列第 2 行变为第 2 列第 3 行。补块后叉号消失，九格由八个发射台和一个着陆台组成。见 `docs/launchpad-floor-verification.json`。

托管运行 `44990561659` 在完整发行包 `db84994` 上完成三艘固定舱十连接回归。十个生存身份全部移动，正常停服读档通过。见 `docs/linux-fleet-verification.json` 与 `docs/evidence/linux-fleet/44990561659-1.zip`。

托管运行 `44990561398` 使用包含船体快照改动的 `db84994` 和一艘预置小船。六帧着陆仪和停服结果经 `qa/home-berths/replay_remote.py` 复核，七颗钻石回港并扣费 12,000 FE。见 `docs/hull-capture-verification.json`。

`docs/shipyard-forbidden-blocks.json` 记录了禁用方块定位。300 格原船的零客户端专服实际标出箱子、旧侧油箱及所在层，两次修补后停服核对货物。首轮 `44992119144` 的箱子挡住准星中央。修正后的托管 `44995223739` 四帧逐像素复核通过，切层定位、旋转、拆箱、重扫和停服七颗钻石均已核对。

`qa/linux-fleet/long-validation.json` 记录了 Linux 长测入口的离线观测窗口、工作站输入保护和 JDK 17 构建检查。实际 48 小时证据待独立主机。

运行 `44991338272` 的关作弊托管中文生存角色从甲板标船位，打开九组月面剖面并辨认天然月石。停服前的最后 25 个采样点集中在同一平面位置，没有原地牢砖交互、首领或钥匙。原始图片、路线及读档摘要在 `docs/evidence/lunar-field/44991338272-1.zip`，汇总在 `docs/hosted-lunar-field-verification.json`。


远端双人生存 `44992119186` 的两个空背包首登、个人合成事件、10 步游标、停服存档及退出已从冻结产物独立回放。见 `docs/hosted-survival-pair-verification.json` 和 `qa/survival-expedition/hosted-pair/replay.py`。

完整的 300 格原船已在零客户端专服从主世界转移到原星际月球。逐阶段耗时和两端停服库存的原区块写在 `docs/hull-capture-verification.json` 的 `wideLunarTransfer`。

300 格原船的提前加载对照在 `docs/source-chunk-preload-verification.json`，含相同出发时刻的空等对照、只加载区块实验和装货后临时换槽的两端原始存档。归档时这项调度只在 QA 夹具中。

`44993373915` 已回放真实坑沿位置、最远离船距离和四张截图，读档仍未发现原地牢交互。见 `docs/hosted-lunar-field-verification.json` 的 `terrainRoutingRun`。

`docs/crew-display-verification.json` 记录 `44995223785` 的原始归档与独立回放。两套实际客户端、非 OP 船员看仪表、船长接管再恢复巡航，工程师关窗后实际炼铁，停服存档保留一块铁锭。

锁定原 GC、ExtraPlanets 和 GalaxySpace 的 657 个原物品、1,257 条注册配方及四种无固定产物的原版配方，见 `docs/lunar-original-locator.json`。未发现可合成的原生地牢定位仪；月面自然搜索使用已加载地表的实际纹理。


双人远端营地 `44993373934` 已从同一世界读到船长游标 35、工程师游标 14。船长做桶后被骷髅射杀，工程师带工台停在树上。原始世界和后续发射台验证入口见 `docs/hosted-survival-yard-verification.json`。 原工作间续档 `45030714868` 的 75/39 游标、实际采木数量与等候点地面也已独立回放。

2026-11-22，300 格大船在正常确认入口调度超时的对照失败与修复后存档回放见 [专服证据](../../docs/confirmed-approach-verification.json)。无游戏客户端，模拟船长，预设近地位置。旧版远端电脑接管 `44998996362` 只完成权限和星图。`812e7e5` 的 `45007800265` 已通过真实电脑、MCP 和人工接管，独立核对输入日志、三张界面和发令来源。[托管接管记录](../../docs/hosted-computer-flight-verification.json)

正常确认的大船区块准备对照见 [船体两端 40 区块及存档](../../docs/prepared-approach-verification.json)，客户端进度条 `45031570333` 已独立渲染并回放确认，两帧蓝色填充实际变长，关服档保留七颗钻石。[渲染和存档](../../docs/wide-landing-progress-verification.json)`45010318632` 同档恢复并用终端认出原地牢砖，尚未触碰。两次十连接回归失败的原档为 `45007800246`（第八人入服后同一船的三条连接断开）及 `45010318505`（诊断还没有连接通道就尝试安装）。下一版在登录完成时安装连接诊断。[月面原图与游标](../../docs/continued-lunar-field-verification.json)

月面同档 `45014160456` 读出工程师正常停服时坐标 Y=44、地牢砖坐标 Y=24 和关服原档。没有接触地牢砖。[续档与停服读档](../../docs/continued-lunar-field-verification.json)

首次同档原地牢砖接触见 `45017370923`（客户端右键和服务端事件均在归档，仍未击败 Boss）。`44ca1d7` 的托管舰队短测 `45014160398` 六十秒窗口通过，十条真实 TCP 连接均移动，三艘船和停服货物均通过。十个身份共用一份 Forge 客户端负载进程。`45017370902` 同场复测通过。

十连接独立托管 `45021809534` 连续首档两小时通过。原 120 个同进程分钟窗口按 `qa/linux-fleet/audit.py` 回放，保存了十份角色数据摘要和三船货物，完整 48 小时需独立测试机。[两小时证据](../../docs/hosted-ten-hours-verification.json)

双舰同刻近地交接的无界面试验已停服复核，每艘 19,522 方块、4,530 方块实体、28 库存，分开落到原月球。组装同刻占用约 1.84 秒。正常服务端勘测试验同刻确认后分刻组装，各保留 40 个地表区块，停服复核通过。共享预载两次各重叠 54 刻，两艘各有 40 个物理存储区块通过核对，第二艘调度提早而第二次原生交接墙钟耗时上升。[计时和两种流程](../../docs/parallel-wide-approach-verification.json)

`45058440198` 用两位普通中文客户端验证同船星图回复不会关闭月面装备页。两次返回按钮、同刻确认和关服双舰回放均通过，组装仍各停顿约 3.7 和 4.4 秒。共享预载后的一艘 300 格船通过同档关服回放。`45054350563` 双中文客户端同刻确认并各自在原月球保有船和货物。该轮缺独立区块审计，`45055660963` 已保留区块并完成独立离线回放。每艘实占 19×8×300、20,103 方块和 4,500 木桶，两人、两份原货物和单次收费均保留。云端断言把替换侧墙的熔炉多算一格，离线复核已纠正。云端两次原生装配耗时 2.65 和 2.78 秒，本机纯专服采样对照为 0.98 和 1.04 秒，30 个装配栈样本中有 29 个在原生方块搬运内。区块优先的移动顺序未稳定降低装配时间，两轮第二次原生跨维都更慢，实验已撤回。[单舰](../../docs/prepared-approach-verification.json) · [双舰](../../docs/parallel-wide-approach-verification.json)

原泊位图关闭后确认仍持有全部 40 个地表区块。`docs/prepared-approach-verification.json` 的 `currentSurfaceRetention` 对应 300 格原船的产物 SHA、服务端动作、原始计时和正常关服档。

`returnedThroughKnownCorridor` 与 `recoveredInKnownCorridor` 记录 `45040178121` 到 `45041631565` 的原月球同档续航。关服后角色生命由 7.84 恢复至 16.07，仍有两把原钥匙。`walkedOffOrdinaryChest` 对应同档复测 `45043762741`，角色从普通箱盖走下并在地牢里新增约 886 格，关服保留两把原钥匙。开原箱、装货和返航未通过。`45056707668` 的原档通过离线区块确认补桥六格，保留原箱、四把原钥匙和满生命。原 Boss 接触和拾取已观测，击杀归属待证。

双人续档 `45058440034` 已独立复核上一段订单前缀及实际原发射台方块。原工作台做出九块，铺两块，七块留在背包。续档目标是补齐原平台、取回原座椅并放置。见 [双人造船证据](../../docs/hosted-survival-yard-verification.json)。

月球续档 `45060947361` 已回放。图纸仍在背包，角色用随身食物恢复满生命和饥饿并爬回井口。地表路线停在独立圆石柱，原岸六格缺口可用随身圆石接续。装货、同船返航和独立首领击杀归属仍待证。[原月球续档](../../docs/continued-lunar-field-verification.json)

原机器 `45064227784` 的中文客户端从生存终端点到跟踪芯片、钛板并成功回退。原专服核对了配方值。新结构读取包含精密装配机 36 个格和轧机 22 个格，各含必须留空的位置。图形摆法另行验证。[配方与运行证据](../../docs/original-machine-recipes-verification.json)

`45065118434` 核对了精密装配机与轧机结构的中文截图、两次实际返回按钮和载入的发行包。修正了材料入口下旧的「返回制油」文案。电弧炉原专服读出钢、钛批次和五层结构，相关界面实测另记。[机器证据](../../docs/original-machine-recipes-verification.json)

`45065889946` 在同一中文无作弊生存客户端从导航电脑点到跟踪芯片、钛板及钛锭，依次查看并返回三种原机器结构。六张截图及工艺来自已选的同一公开构建。[配方与结构验证](../../docs/original-machine-recipes-verification.json)

月面 `45064001712` 已同档带原图纸过井口，回到船边 (1.29,67,0.50)，停服核对原甲板边界与图纸。观察器已补固定舱登船判定。正在独立验证登船后原图纸装货、同船返航以及无钥匙原档的首领击杀。[月面运行证据](../../docs/continued-lunar-field-verification.json)

`45063266054` 同档已铺齐九块原发射台，未放座椅。精密装配机、轧机、电弧炉三张示例结构另在专服真实形成并正常关服。维修入口和人工造船仍按完整旅程独立验收。[发射台](../../docs/hosted-survival-yard-verification.json) · [结构](../../docs/original-machine-recipes-verification.json)

月面无钥匙续档 `45066536466` 正常保存。关服脚印的真实规划器回放得到 162 个路点。当前尚无独立的首领击杀归属证据。[无钥匙记录](../../docs/continued-lunar-field-verification.json)

双人原档 `45068118045` 的两个渲染客户端关闭作弊，工程师取回终端和原座椅并装上中央发射台。已用上一段查验订单前缀和游标 347/728，关服核对九块原发射台和原座椅。[原营地存档](../../docs/hosted-survival-yard-verification.json)

`45068120877` 原月面关服档确认原宝箱 `(394,25,-60)` 由上锁变为解锁，拾取日志有一把原钥匙。缺少首领掉落归属、开箱界面和带回奖励的直接记录。补充监听已编译，必须从 `45066536466` 重新验证。[同档原始证据](../../docs/continued-lunar-field-verification.json)

`45067272621` 从 `45064001712` 同档已证实固定舱识别登船。冷读图纸仍在背包，未装货或返航。`TreasureHands` 已改按登记舰桥定位货箱并记真实操作，续跑待验。[月面续档](../../docs/continued-lunar-field-verification.json)

`45070451400` 从已登船的原月球档实测装入原图纸并返港。`qa/lunar-field/replay.py --world` 已直接核对关服区块、返航收费、乘员与奖励。前段已有钥匙，完整击杀流程另待验收。[原航程证据](../../docs/continued-lunar-field-verification.json)

`45071397550` 已核对导航电脑工作台配方的九格库存提示。预置的正确型号原电路和两份红石填三格，其余六格标缺，截图逐格读图通过。该轮继续点完三台原机器结构。[库存和原配方](../../docs/original-machine-recipes-verification.json)

`45069717679` 已从无钥匙档核对原首领实际掉落、同一钥匙实体拾取、解锁、开箱和取图纸。关服在井口，图纸仍在背包。续档改用已挖月面砖补桥，再装货与返港。[原存档和事件](../../docs/continued-lunar-field-verification.json)

纯专服原包配方核对通过，四块原锡板生成四枚原电池，原电池和熔炉生成原燃煤发电机。错误锡板变种和数量不足都无输出。双人同档取件已由 `45109840580` 实测通过。[配方原始结果](../../qa/survival-expedition/generator-power-verification.json)

`45072727712` 已通过原月面首领、开箱、装货和同船返港。由 `45069717679` 正常关服接续，六格原砖桥及原图纸货箱从存档核实。关服回放通过。[原航程证据](../../docs/continued-lunar-field-verification.json)

`45070006663` 双人备料续档在不受支持的工作台东侧站位超时，船长的木板和木棍已经落盘，工程师保留终端。使用西侧站位从同档继续，游标 353/729。[关服证据](../../docs/hosted-survival-yard-verification.json)

原塔架和组装机已在纯专服用真实工作台配方匹配。每格塔架消耗七根钢杆。示例材料页会显示剩余批次，托管 `45077318198` 已检查已有两格塔架时还需四批。专服同时核对了工作台的控制电路和启动设备配方，同一托管客户端已点击车间电弧炉图标，查看工作台配方和原五层结构。[配方与原始结果](../../docs/original-shipyard-recipe-verification.json)

原煤发电机给原电弧炉供电的无客户端实测通过。原输出口第四格在关服区块内保留一枚钢锭。该试验预摆了五层原结构。[证据](../../docs/original-shipyard-recipe-verification.json)

`45078887349` 已实测电弧炉第五层供电图。原燃煤配方和玩家图形指引分别验证。[截图和供电证据](../../docs/original-shipyard-recipe-verification.json)

2026-11-23 无客户端复测通过两艘 300 格原船同刻确认并分刻装配。关服有两船、两名模拟船长、货物和独立收据。日志仍有区块超时和慢刻。[原始计时](../../docs/parallel-wide-approach-verification.json)

同档双舰复测证明恢复机械状态的逐格 INFO 改为按船汇总后，两个关服船体与收费仍通过核对。JFR 原生装配样本里有 Retrofit 方块实体列表搜寻，尚未修改上游。[原始计时和结论](../../docs/parallel-wide-approach-verification.json)

托管 `45080399428` 已验证日志汇总版双 300 格船的地图确认、存活、货物和独立收据。两次原生装配仍有 2.72、3.06 秒停顿。[托管数据](../../docs/parallel-wide-approach-verification.json)

`45124240202` 双中文生存客户端着陆及独立关服回放通过。两次原生装配用时 3.75 和 5.21 秒，服务端采样集中在 VS 放置船体和清理原位。`LandingSamples.java` 从原 JFR 复核 243 个生产装配栈，未采到旧小样本中的 Retrofit 列表搜索。[双舰证据](../../docs/parallel-wide-approach-verification.json)

`45126106107` 双舰分段计时和独立关服复核通过。每船模板放置内有 663,127 次区块查询，累计分别为 3.12 和 4.45 秒。[计时和证据](../../docs/parallel-wide-approach-verification.json)

`45128986238` 复核大船组装的区块查询复用，两艘整船与货物在停服后保留。两次组装为 4.99、5.58 秒，仍有明显停顿。原日志能复核复用次数。[证据](../../docs/parallel-wide-approach-verification.json)

`45082204741` 已用上一关服档独立回放原双人取水，停服库存保留水桶、空桶和航图终端，原平台、座椅及倒模坑均存在。[双人证据](../../docs/hosted-survival-yard-verification.json)

压板器金矿输入须完整金矿石。专服金矿掉落探针与 `45082405797` 中文配方复测见[原配方证据](../../docs/original-shipyard-recipe-verification.json)。该次两张压板器截图是同一画面，变更后的分刻证据待核对。

`45083644488` 的双人续档已从干台取熔岩并铸造黑曜石，独立回放核对两个订单前缀及黑曜石落盘。[铸造证据](../../docs/hosted-survival-yard-verification.json)

`45084134486` 已核对中文电弧炉结构用量与实际余四批、压板器开关分刻图。配方的「返回机器结构」按钮和后续酿造流程待新构建。[画面与散列](../../docs/original-shipyard-recipe-verification.json)

`45084646456` 中文生存界面已验证抗火、粗制和力量的酿造图，从隔热砖进入并返回。采集和酿造实物仍需续原档。[证据](../../docs/original-shipyard-recipe-verification.json)

`45085460795` 已用「返回机器结构」按钮复测。纯专服压板物理探针已从关服实体区块核对四块变种 5 的原锡板。实际双人生存操作继续核对 `45084490472`。[证据](../../docs/original-shipyard-recipe-verification.json)

`45086664065` 验证新水瓶路线与空瓶配方。实际取水的同档阶段是 `surface-brew-water`。[客户端证据](../../docs/original-shipyard-recipe-verification.json)

`45084490472` 的原机安装和拉杆操作已核对，拾取未通过。角色被幻翼击杀，复测从铸造档通过睡眠避免失眠并在北沿拾取。[失败档与证据](../../docs/hosted-survival-yard-verification.json)

`45088801543` 已按关服档复核两人睡过原床，工程师实际压出并拾取四块变种 5 的锡板，原发射台及座椅保留。`45089806264` 与 `45096760436` 均采到红石，但船长在返营前分别死于熔岩和骷髅。`45109840580` 从完好压板档采回红石并完成实际交付，工程师用原配方做出四枚电池和一台燃煤发电机。[双人复核](../../docs/hosted-survival-yard-verification.json)

`45116338133` 的全原包纯专服确认原炉控制器的两种电路都有工作台配方。分别准备四份铜粉和金粉。原炉的五层结构实物清单和配方证据见[原车间记录](../../docs/original-shipyard-recipe-verification.json)。双人同档制作控制器、装机和第一批原钢待实测。

`45112975556` 采钛未通过，首次阶段保留工程师备料。核对 `build/hosted-survival-45112975556/independent-replay.json` 和 `OrbitCaptain-latest.log` 得知寻路取消。施工新路线从这个已保存的双人档开始。[阶段证据](../../docs/hosted-survival-yard-verification.json)

`45120790242` 中文生存客户端实际点了原压板器原料切换，默认选中背包的三块星际铜矿，未将同 ID 的锡矿计入库存。通电图显示两份原铜粉。[测试及上游变种核对](../../docs/original-shipyard-recipe-verification.json)

`45123123956` 在更正名称后复验通过。中文生存客户端实际往返切换原料，铜矿库存 3，通电预览为两个铜粉。已核对原始按钮记录、成品包来源和两幅图。[复验数据](../../docs/original-shipyard-recipe-verification.json)

`45118019766` 的人没有完成第一个路标，关服船长在 Y=28，两人游标未增加。新试验从上一次原发电机成功档 `45109840580` 的地上营地开浅层矿道。[双人关服记录](../../docs/hosted-survival-yard-verification.json)

`45122980408` 双人采矿的阶梯已落盘，未采到首格金红石。独立回放游标 `455/788`，船长保留铁镐。续档先从原红石洞采钻石做镐。[关服记录](../../docs/hosted-survival-yard-verification.json)

全原包纯专服 `45128529365` 确认原金红石变种 8 须钻石镐采集，原掉落表保留变种 8。`45129601885` 中文生存客户端已跟随金红石页的图标查看钻石镐工作台配方并返回。[专服证据](../../docs/original-shipyard-recipe-verification.json)

`45131472890` 中文客户端点开原压板器的金矿石精准采集页，通过实际配方回到压板器。[界面证据](../../docs/original-shipyard-recipe-verification.json)

`45135020671` 已通过同档钛矿与钻石阶段，船长实际合成钻石镐并带 14 块原矿回营，工程师保留原发电机。[关服记录](../../docs/hosted-survival-yard-verification.json)

动态预留 `45140743415` 未通过，故障发生在模板放置之后的船芯查询，详见[双舰数据](../../docs/parallel-wide-approach-verification.json)。下界门备料 `45136974051` 因船长在采燧石返程死于熔岩失败，从 `45135020671` 继续，详见[同档生存数据](../../docs/hosted-survival-yard-verification.json)。

`45152365961` 普通路径的双 300 格中文客户端及关服回放通过。`45152359175` 动态路径的双舰组装完成，工程师在船舱交接中坠亡，参见[对照结果](../../docs/parallel-wide-approach-verification.json)。

空船位取消与重启通过两轮纯专服关服回放。VS 未保存空船记录，Starbridge 清除旧登记并允许重新申请。没有真人或渲染客户端参与。[双舰数据](../../docs/parallel-wide-approach-verification.json)

`45553030024` 同时确认两艘提前创建的空船未加入 VS 已加载船舶列表。分配器副本预览已由零玩家纯专服验证取消和重启，仍需双舰实测。[双舰数据](../../docs/parallel-wide-approach-verification.json)

`45549555451` 双人关服回放只完成砂砾拾取，船长站在砂砾原格并保留原材料。续档从 651/788 继续。[双人原档](../../docs/hosted-survival-yard-verification.json)

`45554532430` 预测预热已通过双 300 格中文客户端和关服回放，仍有单刻组装停顿。[双舰数据](../../docs/parallel-wide-approach-verification.json)

`45554949807` 托管 Forge 启动画面超时，未登录，无新生存存档。[原始日志位置](../../docs/hosted-survival-yard-verification.json)

`45555582577` 默认配置的双舰预热、同刻确认和原月球关服回放通过。默认配置零玩家纯专服也验证了取消预热和重启时清理旧登记。[双舰数据](../../docs/parallel-wide-approach-verification.json)

本机零客户端 `source-stages-20261125` 在双 300 格模拟玩家专服记录原舱两段清除时间，独立关服审计通过。实际客户端计时另跑。[双舰数据](../../docs/parallel-wide-approach-verification.json)

`45557050030` 双客户端原舱清除和模板计时已完成，原船和货物通过关服审计。[双舰数据](../../docs/parallel-wide-approach-verification.json)

`45558009076` 源区块排列性能对照通过双舰关服审计，耗时未显示稳定收益，保留原组装顺序。[对照数据](../../docs/parallel-wide-approach-verification.json)

`45560919199` 单人中文托管着陆仪保留落点，显示能源不足并在夹具恢复后回港，关服库存和扣费正确。`45556248559` 双人备料中船长被幻翼击杀，继续使用完好的 `45549555451`。[回港证据](../../docs/remote-home-port-verification.json) · [生存证据](../../docs/hosted-survival-yard-verification.json)

`45560243493` 在原砂砾坡的打火石阶段未通过，关服存档确认船长遭幻翼击杀。`45561857436` 继续使用完好原档，改在营地边重复挖砂砾。[原始读档](../../docs/hosted-survival-yard-verification.json)

`45561857436` 原营地外筛砂砾的关服阶段未通过。读角色统计确认三次放置和挖掘，后来船长在屋外被幻翼击杀。[双人生存数据](../../docs/hosted-survival-yard-verification.json)

`45564226863` 同一专服的 10、20、30 连接扩容短测已完成。实际停服读档及独立观测窗口回放通过。[专服数据](../../docs/linux-fleet-verification.json)

`45566571692` 实际中文客户端显示着陆能源储备表，补能回港通过。`45565421721` 双人原档保留全部前置并补好地板，读档定位到玩家身体仍挡着相邻砂砾格。[回港](../../docs/remote-home-port-verification.json) · [双人](../../docs/hosted-survival-yard-verification.json)

`45568023631` 同档打火石合成通过，独立关服回放游标 660/788。后续采黑曜石仍用同一世界。[双人生存证据](../../docs/hosted-survival-yard-verification.json)

`45698929894` 的双人纯生存存档已保存完整下界门，船长亲自往返。回营死亡后，关服数据仍保有 20 块下界岩和原工具的掉落实体。续档验证拾取并核对关服背包。[双人验收数据](../../docs/hosted-survival-yard-verification.json)

`45708810839` 已验证中文安装检查显示实际 Forge / Java 版本，并重新验证旧库恢复以及原包的创造栏和 JEI。`v0.3.0-pre.3` 的包摘要和两份用户实例 JAR 已核对。[实测记录与画面](../../docs/original-inventory-verification.json)

`v0.3.0-pre.3` 使用用户实例的两份实际 JAR 在独立零玩家专服正常启动、保存和退出。读取关服区块确认 Galacticraft 月球与两个扩展各自的谷神星均由原包生成。专服使用可用于服务端的其他模组。[关服数据](../../docs/original-inventory-verification.json)

`45715790557` 的双人生存下界往返通过托管和独立回放。关服保存 21 块下界岩、原工具与原钛矿，下一段继续使用这份存档。[关服结果](../../docs/hosted-survival-yard-verification.json)

`45718564454` 的中文生存客户端从原电弧炉隔热砖逐级点击红砖块、红砖和黏土球，读到黏土来源，打开石铲配方并回到隔热砖。此场景核对实际界面，采料另由双人续档验证。[截图和输入证据](../../docs/original-shipyard-recipe-verification.json)
