# 实测与待验证项目

查看某个版本是否支持某项功能时，先读 docs/test-report.md、docs/expedition-verification.json 和 docs/verification-results.json，再对照测试场景源码。下述数据属于不同运行。Forge 或物理依赖升级之后，需要重新采样。

0.3 实测先看 `docs/original-radiation-verification.json` 的原模组航程、`docs/propellant-guide-verification.json` 和 `docs/device-picker-verification.json` 的玩家入口、`docs/integrated-fleet-verification.json` 的驾驶帧时间及 `docs/concurrent-orbits-verification.json` 的长服进度。下面的历史顺序按各自构建解读。

在真实月面从两处坑沿看见洞口、步行进入并辨认天然月岩的存档和证据见 `docs/lunar-natural-search-verification.json`。该段仍待自然确认首领房和钥匙。

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

托管运行 `44988165497` 已从预置两株甘蔗和备料的七个数值走通自然生长、收割、制纸、制作与使用终端、入箱和关服验存档。原空背包双人档仍在游标 680。见 `docs/survival-terminal-verification.json`。

云端 `44989438528` 的三张船坞截图与停服区块经 `qa/remote-ui/replay_dock_floor.py` 复核。旋转后图上缺口从第 3 列第 2 行变为第 2 列第 3 行。补块后叉号消失，九格由八个发射台和一个着陆台组成。见 `docs/launchpad-floor-verification.json`。

托管运行 `44990561659` 在完整发行包 `db84994` 上完成三艘固定舱十连接回归。十个生存身份全部移动，正常停服读档通过。见 `docs/linux-fleet-verification.json` 与 `docs/evidence/linux-fleet/44990561659-1.zip`。

托管运行 `44990561398` 使用包含船体快照改动的 `db84994` 和一艘预置小船。六帧着陆仪和停服结果经 `qa/home-berths/replay_remote.py` 复核，七颗钻石回港并扣费 12,000 FE。见 `docs/hull-capture-verification.json`。

`docs/shipyard-forbidden-blocks.json` 记录了禁用方块定位。300 格原船的零客户端专服实际标出箱子、旧侧油箱及所在层，两次修补后停服核对货物。中文生存客户端验收待查首轮远端产物。
