# 安装星航 0.3 开发版

在启动器里新建 **Minecraft Java 1.20.1、Forge 47.4.28、Java 17** 的实例。客户端和专服使用相同的一套文件。首次建服前准备好配置，进入游戏后，普通玩家用方块、按键和界面完成操作。

| 新实例中的目录 | 放入的文件 |
| --- | --- |
| `mods/` | 本项目的 `starbridge-1.20.1-0.3.0-dev.jar`、Retrofit `0.6.2-beta`、Valkyrien Skies `2.4.11`、Kotlin for Forge `4.11.0` |
| `retrofit/mods/` | 本项目的 `starbridge-dock-1.7.10-0.3.0-dev.jar`、Advanced Rocketry `1.4.0-32`、LibVulpes `0.2.8-37`、Galacticraft `3.0.12.504` |
| `retrofit/mods/` | 要游玩两个扩展时，加入 ExtraPlanets `2.1.4` 与 GalaxySpace `1.2.15` |
| `mods/` | 需要游戏内电脑时，加入 CC:Tweaked `1.116.2` 的 Forge 发行包 |

从[版本清单](../pack/original-spaceflight.lock.json)里的作者发布页取得对应文件，清单也记录了各自的 SHA-256。此版 Galacticraft 已合并 Core、Planets 和 MicdoodleCore，每个文件只放一份。太空第三人称驾驶由星航提供，其设计参考 UnBound。初次装包可以按表里的组合开始。

**两个扩展一起使用时**，新实例在第一次启动前把[共存配置](../pack/config/ExtraPlanets.cfg)放入实例根目录的 `config/`。已有 `ExtraPlanets.cfg` 的实例请保留原文件，只合并模板中的两项设置。它们启用 GalaxySpace 天体兼容并让 GalaxySpace 提供火星空间站。单人、入服玩家、专服采用相同的配置。

Retrofit 首次启动时准备旧运行库。在独立服务器上，如果同意从 Mojang 和相关 Maven 仓库下载，请在该实例的 `retrofit/retrofit.properties` 中设 `download-files=yes`。先用启动器或 Forge 专服正常启动，等模组加载完再创建或加入世界。生成过的地形会保留在存档中，首航测试建议用新存档。[Retrofit 作者的安装说明](https://github.com/Astryxx/Retrofit#using-it)

**进入游戏后**，拿到指南针，在工作台的配方书里合成“航图终端”，对着空气按使用键打开“首次航行”。可在“舰桥配方”里查看当前世界的合成九宫格。点“造船从这里开始”，按图示辨认原发射台、塔架和火箭组装机。右击组装机能看各层船体，完成扫描后再组装和加注。船内要放自己拥有的舰桥控制台。入轨后右击舰桥，在星图点选月球，查看所需能源，再选择手动或自动驾驶。加注到火箭的推进剂和舰桥的驱动储能是两项读数。进入月面真空前，还要备好当地可用的供氧装备。[实机首航图解](images/first-flight-shipyard.png) · [组装机操作](images/shipyard/02-native-scan.png)
