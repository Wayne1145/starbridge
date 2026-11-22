# Starbridge / 星航

在 **Minecraft Java 1.20.1 / Forge** 用高级火箭（Advanced Rocketry）的发射台、组装机和原火箭发射自己搭建的船，亲自驾驶，去 Galacticraft、ExtraPlanets 和 GalaxySpace 的原世界探索。行星地形、地牢、Boss、钥匙和战利品由对应的原模组提供。

## 安装 0.3 开发版

新建 Forge **47.4.28 / Java 17** 的实例。星航有主包和船坞附属两个文件。Retrofit **0.6.2-beta** 在 1.20.1 宿主中装载指定的 **1.7.10 原包**。客户端和服务器都需要高级火箭、LibVulpes、Galacticraft、Valkyrien Skies 及对应前置。两个星际扩展可以合装。CC:Tweaked 供想编程驾驶的玩家选装。[安装目录、文件和配置](docs/install-original.md) · [版本与校验值](pack/original-spaceflight.lock.json)

船主打开舰桥，在「舰船系统」点选已开机的 CC 电脑可以配对或解除。外部 AI 通过舰桥独立授权。接近行星时，在着陆图查看净空和占用标记，改选泊位后再降落。[舰桥设备列表](docs/images/devices/paired.png) · [泊位改选](docs/images/berths/reselected-map.png)

只看到星航自己的几件物品时，先在主菜单点 **模组 → Starbridge / 星航 → 配置**，打开「首航安装检查」。点任一组件可以查看它提供的玩法、原文件名、安装目录和获取方式。进入世界后，航图终端的「首次航行」里也有同一个入口。[只装主包时的界面](docs/images/flight-setup/main-only.png) · [缺少船坞时的文件位置](docs/images/flight-setup/missing-dock.png)

## 第一趟登月

1. 第一次拿到工作台时，打开原版「进度」，点「星航 · 首次航行」标签。将鼠标移到指南针和终端图标上可看下一步。拿到指南针后，在工作台配方书搜索并制作**航图终端**。对着空气使用它，点「造船从这里开始」切换俯视图和侧视图，照图摆出 3×3 的示例发射台、六格塔架和组装机。点「材料与配方」可以顺着样船零件查看材料和配方，例如从塔架查到钢棒。点「车间配方」还可查看小型压板器、原电池、燃煤发电机和隔热砖，配方会标出所需的药水品种。点「舰桥配方」能看到当前世界实际使用的合成九宫格。放置自己的舰桥控制台，再用终端右击它。绑定后仍能从舰桥上的「首次航行」回看图解。[发射台俯视图](docs/images/first-yard-plan.png) · [塔架侧视图](docs/images/first-yard-elevation.png) · [舰桥配方](docs/images/first-helm-recipe.png)
2. 在发射台上搭船。右击原组装机查看可旋转的分层图，按扫描反馈补齐引擎、油箱、导航电脑和座椅。给原机供电，给原火箭加注推进剂，给舰桥充入驱动能源。单人可自己登船并点「本地入轨」。联机时，每位同行船员先在原火箭上登座，再由船长发射。[组装机操作和缺件示例](docs/shipyard.md)
3. 在舰桥星图点选 **Galacticraft 月球**，选择手动驾驶或巡航。驾驶员在第三人称视角看到自己的船，其他人可以在固定船舱上下楼、开箱和操作机器。靠近月球后，打开着陆仪点选位置，进入近地物理阶段。舱内右击供氧器查看密封与剩余氧气。[太空驾驶席](docs/images/orbital-helm.png) · [着陆仪](docs/images/lunar-approach.png) · [供氧面板](docs/images/lunar-cabin-oxygen.png)
4. 在星图选中月球，点「月面出舱装备」→「打开氧气装备栏」，把面罩、供氧设备和满氧气瓶拖进自己的原装备槽。船员也能从未绑定的终端直接打开。安装 GalaxySpace 后，露天甲板在月球白昼也有辐射。已验证的装备组合是四件 GalaxySpace 太空服，每件用高级火箭化学反应器做气密处理，再配星际供氧装备。[船长的装备入口](docs/images/equipment/helm.png) · [船员的装备入口](docs/images/equipment/crew-guide.png) · [原装备栏](docs/images/equipment/original-slots.png) · [GalaxySpace 防护装备](docs/images/moon-equipment.png) · [双人航程实测](docs/original-radiation-verification.json)

   出发前在同一页点「野外口粮」，可以看见本服工作台配方和背包里有几份。测试组合的原配方是一个星际罐体加两个生马铃薯。安装 GalaxySpace 时，这份罐头能在月面露天吃，普通烤土豆需要有氧环境。[野外口粮](docs/images/survey/lunar-rations.png)

   下船前站在甲板上，手持航图终端，点舰桥星图右上角带地图图标的「地表勘察」，再点「在此标记飞船」。在野外也能按住潜行键再按使用键开图。选「飞船」并关图后，手持终端就能看到返船方向和距离。[舰桥入口](docs/images/survey/helm-survey-entry.png) · [勘察图](docs/images/survey/surface-map.png) 穿好装备后探索月球地牢。击败进化骷髅、拾取钥匙并打开原宝箱，把奖励装上原船。回到舰桥点选「母港」返航。[地牢原宝箱](docs/images/original-moon-reward-menu.png) · [把月球战利品装船](docs/images/moon-cargo-menu.png)

船主可以在舰桥「舰船系统」配对 CC:Tweaked 电脑、授予或撤销外部控制。在太空驾驶席输入推力可以人工接管。单人主机或专服管理员可以按需开启本机的 MCP 服务。[电脑与 MCP 使用方法](docs/mcp.md) · [实际 Lua 和 MCP 操作](docs/computer-flight-verification.json)

## 实测范围

- 两名无管理员权限的生存玩家在两个扩展同时启用时，乘高级火箭的原火箭抵达 Galacticraft 原月球。工程师用首领掉落的钥匙打开原宝箱，装船带回一枚命名牌，返程断线后重新登上原船。测试预置了船体、装备、能源、战斗药效和地牢位置。此前两次航程分别带回火药和小麦。[全扩展联飞](docs/original-radiation-verification.json) · [先前双人航程](docs/original-crew-verification.json)
- 单人新档验证了实际配方解锁、合成与首航界面。在另一个关闭作弊的存档，船长操作了供氧、近地推力和制动，随后第二套客户端成功加入局域网。[单人及联机记录](docs/first-flight-and-lan.json)
- 星图在两个扩展合装时识别到 41 个目的地，含地球。调查过的原世界包括月球、谷神星和金星，三处都已核对原 Boss、钥匙和宝箱。[原星图](docs/star-map-and-dock-verification.json) · [原世界与图纸](docs/original-worlds-production.json)
- 100、200、300 格长的窄船做过原机载货装配和发射测试。300 格样船为 300×7×6，共 6,629 个方块。真实载人小船在抵达时仍出现过明显卡顿。三艘预置船舱的 30 个连接巡航 1,200 刻时，平均 19.995 TPS。另一次测试中，1 个完整客户端和 29 个 Forge 协议连接同时在线，船长驾驶 100×7×6 的载货船往返原月球，85 颗钻石和 40 件炉内物品原数返港。这 85 秒平均为 19.39 TPS。后续两场 30 连接实测中，200×7×6 和 300×7×6 的载货船也通过了原月球往返，平均分别为 19.10 和 19.59 TPS。两场都只有一艘船航行。更宽船型、多舰并发和多日运行仍在验收。[两艘大船的往返与卡顿记录](docs/large-roundtrips-verification.json) · [原船往返与服务器延迟](docs/native-fleet-verification.json) · [三舰连接实测](docs/connected-fleet-verification.json) · [大船测量](docs/loaded-shipyard-verification.json) · [融合大纲与图形界面验收](docs/integration-plan.md)

## 构建与历史

使用 JDK 17 与仓库内的 Gradle wrapper。可用 `./gradlew test build` 构建现代主包。旧侧船坞的构建、验证和安装包复现见[开发指南](CONTRIBUTING.md)。项目通过 Serena 索引 Java，跨会话的进度、决定和原始验证入口保存在仓库内。[项目交接](AGENTS.md) · [变更记录](CHANGELOG.md)

0.2 物理原型、原创勘探维度和当时的诊断命令见[历史远征路线](docs/unbound-roadmap.md)、[原型装配说明](docs/assembly.md)和[历史测试](docs/expedition-verification.json)。星航原创代码及原创纹理遵循 GPL-3.0-only。安装清单列出各上游作者发布的依赖文件及下载来源。[许可证](LICENSE)
