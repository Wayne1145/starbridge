# Starbridge 接手入口

**本机桌面归用户使用。** 保留 `.tools/no-local-game-clients`。在这台 Windows 电脑上只做后台编译、单元测试、读取存档和 `nogui` 专服验证。游戏客户端、Forge 负载客户端、私有桌面、模拟输入和等位启动器均已停用。需要图形或多个连接时，使用独立的 GitHub 托管 Linux 或获准的独立测试机。2026-11-22 曾发生客户端周期性锁鼠，本机停用规则也适用于关联工作树。[工作规则](../../AGENTS.md)

**目标。** Forge 1.20.1 / 47.4.28，Java 17。Retrofit 0.6.2-beta 运行锁定的 1.7.10 上游原包。高级火箭负责原船坞、组装、燃料、座椅和发射。Galacticraft、ExtraPlanets、GalaxySpace 提供自己的世界、怪物、钥匙、宝箱和科技。太空驾驶员在第三人称看见自己的多方块船，船员在固定船舱活动和使用机器。近地交给 Valkyrien Skies。电脑使用 CC:Tweaked，外部代理经舰桥单独授权。首航要以普通生存、关闭作弊走通图形界面、方块和可改绑按键。[融合大纲](../../docs/integration-plan.md) · [安装与上游版本](../../docs/install-original.md)

**从这里恢复。** 仓库 `Wayne1145/starbridge`，开发分支 `codex/original-spaceflight`，草稿 PR #2，受保护的 `main` 要求 `build` 通过。先核对本地分支、最近提交、未提交文件及远端实际作业。使用 `./scripts/serena.ps1 project index .` 和 `./scripts/serena.ps1 memories read start-here .`。每条新的发行结论都要能回到原始截图、操作日志、服务端断言或关服存档。测试预置、全自动渲染客户端和真人首次游玩要明确标出。

| 需要继续的工作 | 可定位的世界、入口和证据 |
| --- | --- |
| 双人从零造船 | 云端 `44992119186` 已独立回放。两个普通玩家空背包起步，各完成十步、做出工作台和木镐，停服存档有 19 和 14 块圆石。材料段 `44993373934` 已保存退出。船长到第 35 步，死亡重生后缺水桶，工程师到第 14 步，附近没有可放工作台的平地。保留同一趟世界接续。`44995223894` 为后续同档运行。合成原材料、组原始飞船、登月并携原奖励回港仍须在这份档里衔接。[双人远征](../../qa/survival-expedition/hosted-pair/README.md) · [首段原始世界](../../docs/hosted-survival-pair-verification.json) |
| 月面自然探索 | `44991338272` 走过九组地形剖面，`44993373915` 做了改进后的坑沿试验。`44995223782` 测新场景。实测必须区分玩家看见原砖、碰触、击败原 Boss、拾取钥匙、开箱与同船回港。这条重建月面的场景有预置装备和起点。继续材料世界时使用其自身的世界和物品。[自然月面实测](../../docs/hosted-lunar-field-verification.json) · [月面验收入口](../../qa/lunar-field/README.md) |
| 舰桥与船坞界面 | `0e1b928` 的双人船员仪表 `44995223785` 已通过。工程师右击自己的船长所拥有的舰桥，观察接管和恢复巡航，随后使用熔炉并带铁锭退出。七帧会合雷达与返港实验已回放通过（`44995223789` 第二次运行，两条预置航迹和一名实际渲染的船长），原组装机禁装方块标记的 `44995223739` 已独立回放。较早叉号有部分被立体图标遮住，修正后四张截图显示准星随船图旋转，修补后消失，木桶仍有七颗钻石。[船员仪表](../../docs/crew-display-verification.json) · [船坞禁装块](../../docs/shipyard-forbidden-blocks.json) · [航线及原港存档](../../docs/remote-home-port-verification.json) |
| 长船及长期服 | 300×19×8 原船在单次冷读跨维中，实际有 19,522 个船块和 4,530 台机器，全部 28 份库存已核对。测量窗口约 2.17 秒。区块预载对照也已从原始存档复核，持续改善正常着陆入口的停顿。[实物跨维](../../docs/hull-capture-verification.json) · [预载对照](../../docs/source-chunk-preload-verification.json) |
| 发行前的独立观测 | 三船十连接短测已通过。10 人两小时、20 人六小时、30 人四十小时，须由同一独立专服进程积满连续观测。旧本机长测主动结束在约 4 小时 42 分，仓库未登记自托管 runner。另需真人首次发现界面并完成修正操作。[长测实机与边界](../../qa/linux-fleet/README.md) · [独立真人试玩](../../qa/first-play/README.md) |

当前可测试的 `0e1b928` 开发包由 GitHub 作业 `44995223791` 生成，本机完整校验的副本在 `build/distributions/0e1b928/starbridge-0.3.0-dev.zip`。构建缓存为仓库下 `.gradle-user`，JDK 为 `C:\Program Files\Java\jdk-17`。

[代码索引](code-map.md) · [验证索引](verification.md) · [测试报告](../../docs/test-report.md) · [本轮整理前的完整交接原文](development-history-2026-11-22.md)

300 格正常确认已修复。模拟非 OP 船长在纯专服调用 SURVEY、收到 CLEAR 后调用 LAND，月面停服档已独立回放。原船货物、船长和单次收费均已核对。下一步解决大船物理装配与跨维的主线程停顿，以及多船确认。独立 Linux `45007800265` 已核对实际 Lua 起航、MCP 巡航、改绑推进键人工接管、双侧拒绝及恢复巡航。[电脑接管实测](../../docs/hosted-computer-flight-verification.json)材料世界 `44998996361` 与月面 `44998996324` 均未完成，检查其原始动作和存档后接续。[正常确认实测](../../docs/confirmed-approach-verification.json)
