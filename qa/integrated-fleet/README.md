# 单人载货船往返与客户端帧间隔

单人玩家关闭作弊创建生存新世界，使用实际注册的高级火箭长船快照，从舰桥开星图前往 Galacticraft 月球，落地后使用已配对的终端返航。每个世界使用一艘船，一次只启动一个前台不可见的 Windows 游戏进程。客户端在渲染线程上连续采样。相邻两次结束一帧的 `System.nanoTime()` 之差包含渲染、等待和驱动的总耗时。

为让三个尺寸可以比较，当前布局均为宽 7 格、高 6 格，窗口 840 × 480、视距 6、模拟距离 5、快速画质、垂直同步关闭、帧率上限 120。测试在有 NVIDIA 驱动的完整客户端上运行。原船的快照、物品和原火箭标识由之前真实原机发射的存档提供。测试预置了供电、装备、回港位置和初始上船位置。

在有本地 Forge 1.20.1 / 47.4.28 库的工作区，从仓库根运行以下命令。`build_fixture.py` 使用已安装的 JDK 17、ForgeGradle 命名游戏库及该项目已验证的运行时名称映射。常规 Gradle 也可用 `./gradlew.bat -I qa/integrated-fleet/fixture.init.gradle reobfIntegratedFleetQaJar` 构建测试包。`run.py` 从已经用于原船回港的隔离客户端模板复用不可变 JAR 和原生库，并复制该长度的原始发射清单。客户端目录没有既有世界、OP 和测试命令。

```powershell
py -3 qa/integrated-fleet/build_fixture.py
py -3 qa/integrated-fleet/run.py 100 trial1
py -3 qa/integrated-fleet/audit.py qa/integrated-fleet/.local/run-100-trial1
```

将 `100` 分别换成 `200`、`300`，待前一个进程保存世界并退出后再开始下一次。每次使用未占用的实例名。测试游戏目录中的 `integrated-flight.fixture` 仅激活此场景。原船恢复、两段航费、脚底碰撞面和每个货柜的核对调用 `qa/connected-fleet` 中已用专服实测的 `FleetScenario`。同一场景只安装一份舰队测试附属，其余依赖见[原包安装说明](../../docs/install-original.md)。

`frames.csv` 保留连续序号及两侧单调时间、测试阶段、实际界面和维度。一个图形过渡跨越两个阶段时，间隔归入起始阶段，同时单列维度切换与阶段边界。每阶段按实际帧数给出 p50、p95 和最大值。累计帧数较多的流畅阶段容易拉低整趟航程的分位数，因此需要同时查看两程驾驶和交接数据。游戏在最后一个返港采样结束后截图，原始采样不含截图开销。

`audit.py` 检查帧间隔连续、实际星图点击、两程碰撞脚底、货物与独立收据，并重新读取停服存档的船体区域、玩家位置和航程账本。`publish.py <100 格实例> <200 格实例> <300 格实例>` 会把测试数据和返港截图写入[验证记录](../../docs/integrated-fleet-verification.json)。各份压缩包包含原始帧、操控输入、服务器断言、显示设置、使用的原船 NBT 和保存信息。实测覆盖的是单人集成服。大型船的局域网访客需另设双人场景。
