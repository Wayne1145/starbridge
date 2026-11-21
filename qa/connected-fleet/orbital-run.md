# 多人舰队的短时巡航测试

这个用例在本机独立 Forge 1.20.1 专服上同时运行三条去 Galacticraft 原月球的航线。三艘固定船舱长 100、200、300 格。一个 Forge 客户端进程在标题界面依次建立 30 个独立游戏连接，各自登录、接收区块、向专服回报位置并应答保活。该用例测的是太空航行中的服务端负载。玩家驾驶界面与船员开箱使用[两套完整图形客户端用例](../orbital-cabin/README.md)验证。

1. 用 JDK 17 在仓库根运行 `./gradlew.bat -I qa/connected-fleet/fixture.init.gradle reobfJar reobfConnectedFleetQaJar`。把正式 JAR 及 `build/qa/connected-fleet/starbridge-connected-fleet-0.1.0.jar` 安装到同一临时目录下的 `server` 和 `LunarTester` 两个新实例，按[原包安装说明](../../docs/install-original.md)准备两端的依赖。
2. 两边的游戏目录各放一个空文件 `starbridge-fleet.fixture`。服务端绑定 `127.0.0.1:25890`，客户端停在标题界面。专服为生存，使用 `online-mode=false`、`enforce-secure-profile=false`、`allow-flight=true`、`max-players=30`、`view-distance=3`、`simulation-distance=3` 和种子 `576618501`。这个固定船舱用例在标记、本机地址与端口匹配时才会生成测试船。
3. 测试生成船体、货柜和熔炉，预充能源，把登录的角色放在甲板上并发起三条巡航。各人数档稳定 100 刻后采样 1,200 刻。服务器生成 `fleet-result.json` 后，正常关闭测试客户端和服务器，运行 `py -3.12 qa/connected-fleet/audit.py <临时目录>`。退出码 0 表示服务端、网络连接、船只航迹、冶炼产出和停服存档都已通过交叉核对。

结果提供刻内耗时与刻间隔的中位数、95%、99% 分位数和峰值，单位为毫秒。利用相邻两档结束时的剩余距离，可以核对三艘船都继续靠近目标。每个连接都有自己的区块接收数、移动包数和保活应答数。测试程序预置了单层船舱、乘员位置和航线起点，未测独立显卡渲染、真实网络往返延迟、着陆交接或长期运行。
