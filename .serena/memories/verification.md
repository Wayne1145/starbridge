# 实测与待验证项目

查看某个版本是否支持某项功能时，先读 docs/test-report.md、docs/expedition-verification.json 和 docs/verification-results.json，再对照测试场景源码。下述数据属于不同运行。Forge 或物理依赖升级之后，需要重新采样。

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
