# 供氧面板、近地驾驶和单人开放局域网的客户端实测

该测试附属在单独的 Forge 1.20.1 实例中使用。用 JDK 17 构建 `./gradlew -I qa/habitat-ui/fixture.init.gradle reobfHabitatUiQaJar`，将 `build/qa/habitat-ui/` 下的产物和生产主包放在两个全新客户端的 `mods/`。两边使用相同的原模组、舱坞附属和共存配置，见[安装说明](../../docs/install-original.md)。

主机实例放置空文件 `starbridge-habitat.host`，另一实例放置 `starbridge-habitat.guest`。两边的 `habitat-port` 文本使用同一个闲置本机端口。用相同的语言设置、不同的玩家名字启动。主机从标题界面创建关闭作弊的普通生存存档 `Habitat`。用例在主世界搭建供氧试验舱，放入两罐氧气并将角色带到舱内。客户端右击通风口，在面板中装入一罐。服务端开一个缺口，客户端读取泄漏提示并关闭面板。接下来用例预置停泊的 VS 小船和船主身份，玩家通过航图终端打开舰船系统、近地驾驶，改绑为 I 的推力键产生服务端手动输入，随后点制动和离开。

主机使用内建局域网模式，共享时关闭作弊。测试附属将监听地址设为回环，以便两个离线测试身份在本机完成验证。主机生成 `lan-ready` 后启动访客，访客应生成 `lan-guest-joined`。主机上的 `habitat-result.json`、`habitat-actions.log` 和 `screenshots/` 记录结果。两边各自的 `habitat-stop` 标记让客户端正常退出。旧原模组、完整生产旅程和两人共同登陆火星不由这个面板用例验证。

在主机实例加空文件 `starbridge-starter.fixture` 可先验证首航。工作台放在舱内，测试预置两件物品所需的原材料。客户端使用原版配方书的填充操作，在真实工作台取得两份合成结果，放置舰桥并用自己合成的终端绑定，最后从舰桥图解返回星图。在 `starter-result.json` 核对合成结果，在 `screenshots/00-first-flight-recipe.png` 查看当时显示的配方。此项单独完成即可得出合成结论。后续近地操纵和局域网需另核对 `habitat-result.json`。

添加主机标记 `starbridge-touchdown.fixture` 会预置一艘 5×5 甲板的物理船和下方的实心平台。主机在登船前等候区块同步，之后只通过实际按键推进、下降。`touchdown-result.json` 要求观测到真实负垂直速度，之后连续 12 刻船底距地接近零且垂直速度很低，此时船员仍在甲板上，船体仍由 VS 模拟。结束时点击制动、离开驾驶台，再验证局域网访客。可和首航标记在同一单人存档一起使用。
