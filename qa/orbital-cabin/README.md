# 复测太空驾驶席和多人船舱

这项开发测试预置 300 格长、6 格宽的固定船舱和一段太空航程。两个生存身份由完整 Minecraft 客户端登录，脚本调用按键、屏幕控件和方块交互。服务器核对航行时的转向、制动、登上另一层甲板、打开两个容器及远端熔炉产物。原始观测在[驾驶实测记录](../../docs/orbital-controls-verification.json)。

准备方法与[开发指南里的双客户端环境](../../CONTRIBUTING.md)相同。使用 JDK 17，运行前准备三个独立实例目录，将它们放在同一父目录下并分别命名为 `server`、`OrbitCaptain` 和 `OrbitEngineer`。每个目录放一个空的 `starbridge-orbit.fixture`。两套客户端可分别使用另一个相同提交的工作树。普通发行构建按现有流程执行，下面的 Gradle 初始化脚本只给开发运行添加测试场景。

在专服新目录准备 `eula.txt` 和 `server.properties`。服务端使用 `127.0.0.1:25586`、`online-mode=false`、`enforce-secure-profile=false`、`spawn-protection=0`、`max-players=2`，将 `level-type` 设为 `minecraft:flat`，`gamemode` 为 `survival`，`simulation-distance` 为 `4`。两个开发身份都使用空的玩家数据和空的 `ops.json`。将客户端语言设为 `zh_cn`，GUI 比例设为 2，沿用开发指南内跳过首次引导和允许后台渲染的设置。客户端到达标题画面后会连接本机测试服。

在三个工作树中依次使用下面的命令，把示例中的实例路径换成同一个实际父目录。等服务器日志出现 `Done` 后启动客户端。原 b1 场景同时有 VS、UnBound 和 CC:Tweaked，因此复现时保留这组运行依赖。

```powershell
./gradlew.bat -I qa/orbital-cabin/fixture.init.gradle runServer -PserverDir=D:/lab/orbit/server -PwithShips -PwithComputer -PwithUnbound
./gradlew.bat -I qa/orbital-cabin/fixture.init.gradle runClient -PclientDir=D:/lab/orbit/OrbitCaptain -PclientName=OrbitCaptain -PwithShips -PwithComputer -PwithUnbound
./gradlew.bat -I qa/orbital-cabin/fixture.init.gradle runClient -PclientDir=D:/lab/orbit/OrbitEngineer -PclientName=OrbitEngineer -PwithShips -PwithComputer -PwithUnbound
```

成功的服务器把 `pass: true` 写入 `server/orbit-result.json` 并自行停服。检查 `manual`、`cruise`、`brake`、`climbedUnderWay`、`chestOpened`、`furnaceOpened`，并确认 `smelted` 和 `aftFurnaceStone` 都至少为一。两个客户端分别保存 `orbit-ui-actions.log` 和 `screenshots`。测试结束后关闭这两个测试客户端。
