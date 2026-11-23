# Starbridge 接手入口

**本机桌面归用户使用。** 保留 `.tools/no-local-game-clients` 和已停用的 Windows MCP 桌面控制助手。过往本机自动化曾导致鼠标周期性被锁。此工作站仅用后台构建、读档、单测及 `nogui` 专用服务器。图形客户端、输入、连线压测均走 GitHub 托管 Linux 或已获准的独立测试机。[工作规则](../../AGENTS.md)

**目标。** Forge 1.20.1 / 47.4.28，Java 17，Retrofit 0.6.2-beta。保留高级火箭的原机器、扫描、装配、加注与起飞。Galacticraft、ExtraPlanets、GalaxySpace 实际提供世界、首领和奖励。玩家第三人称驾驶自由搭建的船，船员可在固定舱内走动和操作机器，近地使用 Valkyrien Skies。普通生存用游戏界面、方块和改绑按键完成全程，CC:Tweaked 与 MCP 是可选舰桥接口。[融合大纲](../../docs/integration-plan.md) · [锁定安装](../../docs/install-original.md)

**当前分支。** 公开仓库 `Wayne1145/starbridge`，草稿 PR #2，`codex/original-spaceflight`。`main` 受保护且要求 `build`。先核对 Git、正在运行的作业、实际日志与未提交文件，再回读 Serena。`./scripts/serena.ps1 project index .` 使用仓库维护的单独运行设置。构建使用 `C:/Program Files/Java/jdk-17` 与 `.gradle-user`。

| 路线 | 最新已验证节点与接续动作 |
| --- | --- |
| 同一双人新档 | 从空背包同档做出终端、原发射台、座椅、盾、两桶及倒模。`45082204741` 在干岸取水，`45083644488` 在干台装熔岩并铸造，独立回放游标为 397/746。`45084490472` 安装了原压板器和拉杆并压掉锡块，但未拾到锡板，两人均被幻翼击杀。`45088801543` 回到未丢装备的 `45083644488`，两人先睡原营地床，工程师装机、通电并拾到四块原变种 5 的锡板。关服游标 401/764。`45089806264` 已从该档跑 `surface-power`，查验成功后接 `surface-metals`。测试不开作弊，涉选项或构建均读 [续档规程](../../qa/survival-expedition/hosted-pair/README.md)。资料见[双人原档](../../docs/hosted-survival-yard-verification.json)。 |
| 原车间 | 无玩家专服确认原压板消耗锡块并掉四块原锡板。原电弧炉曾用燃煤发电机和煤实际炼出第一锭钢，双人新档仍需同存档制原设备、接电和取出钢。物品来源须来自生存存档的操作链。原营地箱已有原铝矿、铜杆及其他材料。`surface-brick-stock` 已为酿造后的六批耐火砖准备普通砖供应，需接下界取料回营的同档。远征种子东侧原堡垒有烈焰人刷怪笼及地狱疣，实际传送门落点和路径未验证。`surface-brew-reagents` 使用该堡垒作为目标。`surface-brew-drinks` 通过原酿造台实物槽制作六瓶普通抗火和六瓶普通力量。`surface-blast-bricks` 通过同档原料手摆六批隔热砖。上述续档已经编译，等待同档完成前置。[原配方及实测](../../docs/original-shipyard-recipe-verification.json) |
| 材料和图形界面 | 中文客户端 `45084134486` 确认五层图带入隔热砖 17/81 库存与四批，分刻图确认金矿精准采集提示和通电原配方两份金粉。`45084646456` 从砖配方点抗火、力量与粗制药水。`45085460795` 实际点「返回机器结构」。`45086664065` 已实际点水瓶、空玻璃瓶与玻璃配方，并回机器结构。`surface-brew-water` 已编译，接普通红砖备料，工程师实际采砂、做瓶并装十二瓶水。[界面证据](../../docs/original-shipyard-recipe-verification.json) |
| 地牢与返航 | `45069717679` 已观测同一原首领、掉钥匙、拾取、开箱取原图纸。`45072727712` 同档实际装船载图纸返回。这个档从预置船和装备的原月面开始，尚需将新档造船和整段月面路线串成同一次航程。[月面存档](../../docs/continued-lunar-field-verification.json) |
| 长船与发行 | 已独立复核两艘约 2 万方块、300 格船的着陆和货物。中文双舰 `45080399428` 原生装配仍约 2.72、3.06 秒，JFR 小样本定位 Retrofit `WorldGlue.listTile`。原包性能与船上功能改动要用相同场景复测。[双舰证据](../../docs/parallel-wide-approach-verification.json) |
| 多人和真人 | 十连接两小时独立回放已经通过。仍需独立机同一专服完成十人两小时、二十人六小时、三十人四十小时，现有托管 runner 单次时限不足。上次核对仓库无自托管 runner。真人首次发现入口与自行修正也待验收。[专服长测](../../qa/linux-fleet/README.md) · [试玩规程](../../qa/first-play/README.md) |

[完整历史和此前运行](development-history-2026-11-23.md) · [代码索引](code-map.md) · [验证索引](verification.md) · [测试报告](../../docs/test-report.md)
