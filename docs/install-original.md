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

**第一次拿到工作台后，在暂停菜单点「进度」，打开「星航 · 首次航行」。**

1. 拿到指南针，在工作台配方书合成「航图终端」，对着空气按使用键打开「首次航行」。「舰桥配方」会画出当前世界的合成九宫格。[取得指南针前的进度页](images/first-steps/before-compass.png) · [终端使用键提示](images/first-steps/using-the-slate.png)
2. 点「造船从这里开始」。在「俯视」看 3×3 示例平台和两侧设备，在「侧视」看六格塔架以及高出平台一格的组装机。在平台上搭好自己的船，把自己放置的舰桥控制台装进船内，用终端右击舰桥绑定。想知道零件怎么做，点「材料与配方」，再点塔架或它的原料查看实际配方。点「车间配方」选小型压板器，再点「压板器摆法」。侧视图可以预览拉杆通电前后的锡块和产物。[压板器摆法](images/materials/press-diagram-off.png) · [通电与产物](images/materials/press-diagram-on.png) · [塔架配方](images/materials/tower.png) · [俯视图](images/first-yard-plan.png) · [侧视图](images/first-yard-elevation.png)
3. 右击火箭组装机查看各层船体。打开「首次航行」→「起飞补给」可查看三个步骤。将原燃煤发电机紧贴组装机，右击发电机投煤。需要制作推进剂时，点「火箭加注」→「制作火箭推进剂」。可查看电解机、化学反应器每批的原配方。点「流体口装桶」，依次看供水、取气、送气、取推进剂四张图。上格放入图里的桶，下格取走成品。两台机器供电后，还要在各自的原控制器中点开电源。原火箭扫描通过后组装，手持推进剂桶点「加注」。[发射准备](images/fuel/packed-dock.png)会显示原火箭的实际油量、容积和就座人数。[取推进剂图](images/fuel/bucket-guide-fuel.png) · [原机产出的桶](images/fuel/fuel-bucket.png) 每位船员各需一把原座椅。空出主手点「登船」，全员就座后由船长点「本地入轨」。舰桥的驱动能源需要另行充入，星图会显示航线所需能量。[供电图](images/supplies/generator.png) · [组装机界面](images/shipyard/02-native-scan.png)
4. 入轨后在舰桥星图选「月球」，点「月面出舱装备」→「打开氧气装备栏」。把面罩、供氧设备和两只满氧气瓶拖进自己的原装备槽。船员可对着空气使用未绑定终端，从「首次航行」打开自己的装备栏。启用 GalaxySpace 月面辐射时，实测成功的组合还包括四件 GalaxySpace 太空服，每件在高级火箭化学反应器中用一颗钻石做气密处理。[舰桥上的装备按钮](images/equipment/helm.png) · [普通船员的入口](images/equipment/crew-guide.png) · [原装备栏](images/equipment/original-slots.png) · [GalaxySpace 防护装备](images/moon-equipment.png)
5. 从星图选择手动驾驶或自动驾驶。接近月球后，在着陆仪上选择泊位，确认船体所占的地面净空、距离和速度满足条件再点「开始降落」。有船先占泊位时，地图会画出橙色叉。点另一个地点重新勘测，看到空心准星后再确认。[泊位被占](images/berths/occupied-map.png) · [改选后的泊位](images/berths/reselected-map.png)

   下船前站在甲板上，手持航图终端，在舰桥星图右上角点「地表勘察」和「在此标记飞船」。从这里点「完成」会回到原来的航线。野外可按住潜行键再按使用键开图。[舰桥上的地图入口](images/survey/helm-survey-entry.png) 步行勘察后在图上选「飞船」，关图后手持终端就能看到回程方向和距离，切换到工具会收起指针。[步行返船指针](images/survey/walking-bearing.png) · [勘察图](images/survey/surface-map.png) 探索回来，把战利品装船。船长用已绑定的终端选「母港」返航。

选装 CC:Tweaked 后，将电脑接到舰桥并开机。船主打开「舰船系统」，点显示器图标上的「配对 #…」即可选中它，点「解除」可收回权限。用「授权 AI 操控」单独允许已启用的 MCP 服务控制飞船。普通手动驾驶和星图上的自动巡航都可以直接使用。[配对后的舰桥](images/devices/paired.png)
