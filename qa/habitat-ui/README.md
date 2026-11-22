# 供氧面板、近地驾驶和单人开放局域网的客户端实测

该测试附属在单独的 Forge 1.20.1 实例中使用。用 JDK 17 构建 `./gradlew -I qa/habitat-ui/fixture.init.gradle reobfHabitatUiQaJar`，将 `build/qa/habitat-ui/` 下的产物和生产主包放在两个全新客户端的 `mods/`。两边使用相同的原模组、舱坞附属和共存配置，见[安装说明](../../docs/install-original.md)。

主机实例放置空文件 `starbridge-habitat.host`，另一实例放置 `starbridge-habitat.guest`。两边的 `habitat-port` 文本使用同一个闲置本机端口。用相同的语言设置、不同的玩家名字启动。主机从标题界面创建关闭作弊的普通生存存档 `Habitat`。用例在主世界搭建供氧试验舱，放入两罐氧气并将角色带到舱内。客户端右击通风口，在面板中装入一罐。服务端开一个缺口，客户端读取泄漏提示并关闭面板。接下来用例预置停泊的 VS 小船和船主身份，玩家通过航图终端打开舰船系统、近地驾驶，改绑为 I 的推力键产生服务端手动输入，随后点制动和离开。

主机使用内建局域网模式，共享时关闭作弊。测试附属将监听地址设为回环，以便两个离线测试身份在本机完成验证。主机生成 `lan-ready` 后启动访客，访客应生成 `lan-guest-joined`。主机上的 `habitat-result.json`、`habitat-actions.log` 和 `screenshots/` 记录结果。两边各自的 `habitat-stop` 标记让客户端正常退出。旧原模组、完整生产旅程和两人共同登陆火星不由这个面板用例验证。

在主机实例加空文件 `starbridge-starter.fixture` 可先验证首航。工作台放在舱内，测试预置两件物品所需的原材料。客户端使用原版配方书的填充操作，在真实工作台取得两份合成结果，放置舰桥并用自己合成的终端绑定，最后从舰桥图解返回星图。客户端还会点开造船示例，切换俯视和侧视并抓取两张游戏画面。在 `starter-result.json` 核对合成结果和 `visitedBothYardViews`。安装原月球时，角色还会在舰桥选中月球，点「月面出舱装备」，返回同一星图。`visitedLunarKitFromChart` 与 `00-lunar-equipment.png` 记录这个入口，在 `screenshots/00-first-flight-recipe.png` 查看当时显示的配方。此项单独完成即可得出合成结论。后续近地操纵和局域网需另核对 `habitat-result.json`。

添加主机标记 `starbridge-touchdown.fixture` 会预置一艘 5×5 甲板的物理船和下方的实心平台。主机在登船前等候区块同步，之后只通过实际按键推进、下降。`touchdown-result.json` 要求观测到真实负垂直速度，之后连续 12 刻船底距地接近零且垂直速度很低，此时船员仍在甲板上，船体仍由 VS 模拟。结束时点击制动、离开驾驶台，再验证局域网访客。可和首航标记在同一单人存档一起使用。

同一首航主机添加 `starbridge-materials.fixture`，客户端会从造船图选择材料，检查原发射台的三种配料、原塔架的七根钢棒，点开钢棒配方后原路返回。`materials-ui-result.json` 和 `screenshots/00-materials-*.png` 记录屏幕和操作。

添加 `starbridge-field-survey.fixture` 可用合成的航图终端标记飞船。角色会实际走到另一列方块，潜行使用终端，记录勘察点并按图返船。检查 `field-survey-result.json`，正常退出后还可从玩家存档里的终端读取两枚标记。

`starbridge-supply.fixture` 使用已放置的原燃煤发电机、空组装机以及煤和岩浆桶。玩家打开原发电机投煤并查看组装机 RF，再用岩浆桶给自己做的舰桥充能。原机读数、实际入账、回到玩家背包的空桶分别见 `assembler-powered.json`、`supply-server.json` 和停服后的玩家存档。

已安装原模组时，在首航主机同时放置 `starbridge-materials.fixture` 和 `starbridge-workshop.fixture`。角色从塔架打开车间图，核对隔热砖的两种药水、发电机和电池，再返回塔架。用例另提供一个活塞与三个铁锭，角色在真实工作台配方书中输入本地化物品名、点击搜索结果、成品格和背包空格。核对 `materials-ui-result.json`、`workshop-craft-result.json`、正常退出后的玩家背包和 `screenshots/00-workshop-*.png`。

补给主机再加 `starbridge-fuel-guide.fixture`，玩家从加注页打开推进剂生产图，检查服务器提供的两道原机配方、两份原控制器工作台配方并返回同一补给页。`propellant-guide-result.json` 和四张 `propellant-*.png` 记录批量及画面。[配方与范围](../../docs/propellant-guide-verification.json)

在上述车间合成主机再放 `starbridge-press-cycle.fixture`，客户端会点开压板器摆法、切换通电再返回，然后在黑曜石上放原锡块、刚做的压板器和拉杆。按一次拉杆后检查 `press-cycle-server.json` 的原料消耗和四片锡板，再从正常保存的区块、玩家背包复核。[回归记录](../../docs/press-operation-verification.json)

主机首航场景增加 `starbridge-lunar-rations.fixture` 后，角色会从原月球星图打开「月面出舱装备 → 野外口粮」，核对当前世界实际配方、物品变种和返回装备页的动作。屏幕截图为 `screenshots/ration-recipe.png`，原材料列表在 `ration-recipe-result.json`。[月面露天进食和口粮界面实测](../../docs/lunar-rations-verification.json)

在首航主机添加 `starbridge-chart-survey.fixture` 可从自己放置并绑定的舰桥上点「地表勘察」。客户端选择月球，记录脚下的位置，返回同一张星图并进入月球装备页面。随后立即关闭一次刚请求地图的舰桥，空手右击自己的舰桥检查地图按钮的中文提示，再用配对终端重新打开。`chart-survey-result.json`、`habitat-actions.log` 和 `screenshots/helm-survey-*.png` 记录实际操作。请用停服后 `saves/Habitat/level.dat` 里终端的标记和角色游戏模式核对结果。[本次实测](../../docs/helm-survey-verification.json)

流体口的中文图解用 `run_propellant.py` 在已有的 `HabitatCaptain` 配置副本中打开。详见[四种装桶操作及原口变种](../../docs/bucket-transfer-verification.json)。实际制造和加注的专服用例在 [native-fuel](../native-fuel/README.md)。

首航进度的独立用例打开 `starbridge-habitat.host`、`starbridge-starter.fixture` 和 `starbridge-first-steps.fixture`。`run_propellant.py --first-steps` 会把独立配置的使用键改为 U。玩家从木桶分别取工作台和指南针，点原版进度标签，在真实工作台做出终端，悬停终端图标后按 U 打开造船图。检查 `first-steps-result.json`、`starter-result.json` 和存档的 `advancements/`。[原始结果](../../docs/first-steps-verification.json)

给 `run_propellant.py` 添加 `--layouts`，客户端将从能源指南点开原机的结构摆法。`audit_layout.py <图形用例目录> <原机生产存档目录>` 将渲染出的 19 格对照已保存的两台机器。
