# 原版星际运行记录

## 正式包的安装与三个原地牢

Forge 1.20.1 双端使用相同的 Starbridge 正式构建和 Retrofit `0.6.2-beta`。玩家自建船的近地物理另需项目锁定的 Valkyrien Skies 与 Kotlin for Forge。Galacticraft、Advanced Rocketry、LibVulpes、ExtraPlanets、GalaxySpace 使用作者发布的 1.7.10 原包，均放进游戏目录的 `retrofit/mods`。Starbridge 发行 JAR 只包含本项目代码和资源。[原运行依赖使用的统一版本清单](../pack/original-spaceflight.lock.json)

服主可以先在作者页面取得清单内文件，把它们放在同一个下载目录。在客户端实例与服务端实例分别运行 `python tools/original_world_profile.py --instance <实例目录> --sources <下载目录> --install`。工具核对每个 SHA-256，安装缺失的已验证文件，只设置 ExtraPlanets 与 GalaxySpace 共存需要的两个布尔值，保留其余设定。只检查时使用 `python tools/original_world_profile.py --instance <实例目录>`。Forge、Starbridge 与物理依赖按[开发与安装说明](../README.md)安装。游玩时从游戏内航图终端和原组装机进入。

`0.3.0-dev` 的种子和原首领房适配已随主 JAR 打包。第一次启动会在存档的 `data/starbridge-original-terrain-v1.txt` 记录已经有区块的 Retrofit 维度。这些星球后续生成沿用旧的地形种子。首次探索的原星球采用该存档的真实种子。新存档不需要额外种子测试 JAR。地牢装饰跨区块时，附属模组指定的原首领方块实体随原方块一起保存。

| 原世界 | 自然首领及刷怪器坐标 | 玩家实际解锁 |
| --- | --- | --- |
| Galacticraft 月球 | 进化骷髅 `(396,27,193)` | 原一级钥匙 `galacticraftcore:item.key`，右击打开 `(369,25,190)` 的一级箱 |
| GalaxySpace 谷神星 | 烈焰首领 `(102,32,306)` | `galaxyspace:item.tierkeys`，右击打开 `(76,30,269)` 的 GS 四级箱，原宝箱中还存有 ExtraPlanets 四级火箭图纸 |
| ExtraPlanets 金星 | 进化熔岩怪 `(168,27,894)` | `extraplanets:t4key`，右击打开 `(187,25,888)` 的四级箱，原宝箱中还存有金星漫游车图纸 |

上述三个场景各由一套完整客户端操作非管理员生存角色，使用种子 `576618501`。专服只预置装备与入场位置，捡钥匙后将角色送到箱边。攻击、拾取、开原容器和取物由客户端实际触发。服务端分别核实三次钥匙掉落、箱锁解除与战利品入包。图纸名称取自同份存档的旧注册表。整船将战利品带回母港，以及多玩家同时参战仍需另测。[三次服务器结果、玩家按键与原宝箱数据](original-worlds-production.json)


2026-11-22 在 `run-upstream-original` 隔离 Forge 1.20.1 / 47.4.28 服务端以 JDK 17.0.8 启动 Retrofit。日志位于 `run-upstream-original/logs/latest.log`。当前研究复本和哈希在 `runtime/upstream-evidence`。游戏进度的验证项以实测结果为准。

| 文件 | 作者发行标识 | SHA-256 |
| --- | --- | --- |
| retrofit-forge-0.6.2-beta.jar | Astryxx/Retrofit `BETA-0.6.2`, commit `6516fc7fe152639771d2c16e7081f1b132a6506c` | `a1c46695482fb706595a007bf0500bf18d86f9cc2b9eeb9084c3ea5de64add21` |
| Galacticraft-1.7-3.0.12.504.jar | CurseForge `564236/4501439`, Core、Planets、MicdoodleCore 在同一包内 | `07309d8d7070b3431b94050c7065844d02b93459954707d0d06619e97042576f` |
| ExtraPlanets-1.7.10-2.1.4.jar | CurseForge `241291/3214411` | `469f68ddd8bb1bcc251bea9a7577676ebf9a87d598a2a32d00cf2fdfb647b6af` |
| GalaxySpace-1.7.10-1.2.15.jar | CurseForge `238770/3806537` | `bd2ab595c973197b77474bb3c6ab030f59bb36de915aedcbc36d4175c3d16054` |

Galacticraft 3.0.12.504 原包的配置类默认用 `idDimensionMoon=-28`。Retrofit 的 `LegacyDimensions.levelKey` 会映射到 `retrofit:legacy_dim_-28`。配置可以改 ID，且 Retrofit 会保留已移除目的地的维度类型。运行时还需核实原模组的世界提供器。[兼容层源码](https://github.com/Astryxx/Retrofit/blob/6516fc7fe152639771d2c16e7081f1b132a6506c/forge/src/main/java/retrofit/forge/world/LegacyDimensions.java) · [Galacticraft 发行](https://www.curseforge.com/minecraft/mc-mods/galacticraft-legacy/files/4501439)

独立服在 00:07:13 完成启动。服务端 FML 注册 Micdoodlecore、GalacticraftCore、GalacticraftMars，并记录 `Moon → retrofit:legacy_dim_-28`、`Mars → retrofit:legacy_dim_-29`、`Asteroids → retrofit:legacy_dim_-30`。测试存档 seed `576618501`。未调整种子传递的运行最终抽查了 775 个完整月球区块，读到了原版 `galacticraftcore:tile.moonblock` 地层和 `meta=14` 地牢墙壁。原地牢内有普通箱子与刷怪笼，这批区块没有原首领或上锁的奖励箱。
单独加载 ExtraPlanets，专服生成 `retrofit:legacy_dim_-13` 的 25 个完整区块，其中有 `extraplanets:tile.mercury` 和 `extraplanets:mercurygravel`。单独加载 GalaxySpace，专服生成 `retrofit:legacy_dim_-1007` 的 25 个完整区块，含 `galaxyspace:ceresblocks`。记录分别在 `run-upstream-extraplanets/original-probe.json`、`run-upstream-galaxyspace/original-probe.json` 和各自同目录的控制台日志。这两次是无人专服世界生成检查。

合装另建第三份实例，启动前在 `config/ExtraPlanets.cfg` 设 `B:"Enable New Galaxy Space Compatibility (v1.2.3 and above)"=true` 和 `B:"Mars SpaceStation"=false`。前者保留两个扩展各自的天体，后者让 GalaxySpace 提供火星空间站。只开前一项时，Retrofit 会以“Two space station types registered with the same home planet ID: -29”为由排除整个 GalaxySpace，宿主仍能启动。两项都设好后，旧 Forge 加载 8 个模组，原月球、ExtraPlanets 水星 `-13` 和 GalaxySpace 水星 `-1005`、谷神星 `-1007` 一起注册。两份控制台证据是 `run-upstream-combined/first-start-duplicate-mars.log` 与 `run-upstream-combined/original-probe-console.log`。完整双人游戏流程待测。

高级火箭 `1.4.0-32`、LibVulpes `0.2.8-37`、Galacticraft 与两个扩展合装后，另一次独立服务端运行还加载了 UnBound `0.16.1`、GeckoLib `4.8.4`、Valkyrien Skies `2.4.11` 和 Kotlin for Forge `4.11.0`。旧 Forge 列出的 11 项均为 Available，原月球区块实际加载、保存并正常退出。该实例没有玩家与 Starbridge 开发包。[各模组与原始日志](original-runtime-verification.json)

## 自然生成的月球首领房

Retrofit `0.6.2-beta` 的地形阶段把 `LegacyDimensions.ChunkView` 交给旧生成器。其默认 `getSeed()` 返回 `0`。装饰阶段的 `RegionHostWorld.getSeed()` 读取宿主种子。原月球生成器根据种子推算地牢位置及房间排列。在同一种子 `576618501` 的两份独立存档中，预期入口区块 `(29,16)` 在未调整的版本只有月岩，启用测试用种子适配后出现原地牢砖。未调整的 775 个区块共保存 504,509 个兼容层方块实体。该采样不能用于估算长期服务器容量。

在新存档预加载预期入口周围 169 个完整区块后，服务端自然生成了以下原方块实体。首领刷怪器在 **(396, 27, 193)**，原存档数据记录 `Dungeon Boss Spawner`、`bossClass=micdoodle8.mods.galacticraft.core.entities.EntitySkeletonBoss`、`spawned=0` 和 `defeated=0`。**(369, 25, 190)** 的一级原宝箱记录 `Treasure Chest`、`tier=1`、`isLocked=1` 和空物品列表。这是玩家进房前的状态，地牢和宝箱均未用命令放置。生成器适配的独立构建及复现步骤见[月球生成对照](../qa/retrofit-lunar-seed/README.md)。[原始数据与校验值](original-runtime-verification.json)

## 玩家打首领和打开原宝箱

`run-lunar-encounter-a4` 使用一名非管理员生存角色及完整图形客户端。Forge 47.4.28 专服上的原生刷怪器在角色进房后生成 `galacticraftcore:evolvedskeletonboss`。客户端在攻击距离内挥剑，服务器记录首领在 `(396,25,193)` 掉落一级钥匙 `galacticraftcore:item.key` 以及玩家捡起钥匙。玩家对原宝箱使用钥匙，随后打开原容器，从格子里取出两颗铁锭。原宝箱的 `isLocked` 从 1 变为 0，最终玩家物品栏有 `minecraft:iron_ingot x2`。该次观察中原刷怪器的 `defeated` 位为 0，跨重启或再次进房的状态仍待单独验证。[玩家操作、存档断言与校验值](original-runtime-verification.json)

该用例向玩家提供终端、装备与药效，并把角色放到首领房、拾取钥匙后放在箱边。过程中没有聊天命令。首次连接测试服超时，重连后完成了游玩。先前的对照在月球加载界面反复报旧客户端缺少 `-28` 的提供器。Starbridge 的客户端适配仅在受测 Retrofit 版本、远程服务器公布相应维度、本机已注册对应的原提供器类型时补登记。原月球的房间、刷怪、钥匙、宝箱和战利品仍由 Galacticraft 提供。

[复测首次航行与原宝箱操作](../qa/lunar-encounter/README.md)

两次后续测试复用了玩家战胜首领后的同份物品栏和原宝箱。非管理员角色从谷神星原箱取出 `extraplanets:schematictier4`，从金星原箱取出 `extraplanets:item.schematicvenusrover`。测试程序将原 NASA 工作台放到主世界并送角色到旁边。玩家进入原工作台的“解锁设计图”页面，放入从箱内拿出的图纸并点击原按钮，旧 Galacticraft 玩家数据分别新增 `6666`（四级火箭）与 `6674`（金星漫游车）页面。原按钮操作前后的注册页与原客户端输入记录在 [schematicFollowups](original-worlds-production.json)。
