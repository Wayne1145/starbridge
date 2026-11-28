# 原机生产推进剂与船坞仪表

这个用例在独立的 Forge 专服和中文生存客户端上使用高级火箭与 LibVulpes 的原机器。测试预置煤、水、空桶和多方块结构。玩家通过原控制器打开电源，在流体口的上格放桶、下格取件，最后在组装机上点击扫描、组装、加注。核对服务器读数后，再从停服的原火箭实体和同一玩家的存档读取油量及空桶数。

从仓库根运行以下命令。`--runtime` 指向已有完整上游依赖、含 `server` 与 `LunarTester` 目录的测试实例。`--label` 要使用新的名字。

```powershell
.\gradlew.bat --offline -I qa/native-fuel/fixture.init.gradle reobfNativeFuelQaJar
py -3 qa/native-fuel/run_windows.py --runtime run-native-fuel-factory2 --label workshop --bundle build/distributions/starbridge-0.3.0-dev.zip --java 'C:\Program Files\Java\jdk-17\bin\java.exe'
```

如果原机已产出部分燃料、玩家尚未取桶、游戏正常保存并退出，可以给新的运行加 `--resume-from run-native-fuel-factory2`。启动器读取已保存的流体种类、数量、机器位置和玩家身份，复制并逐个比对世界文件。副本里保留原机器、原玩家和库存。两个机器区块保持加载，产满一桶后才启动客户端。`--inspect-from run-native-fuel-recovered-a` 则用于用候选版本打开已经加注并保存好的原火箭，检查组装后的界面和蓝图返回按钮。

[两段生产及停服存档核验](../../docs/native-propellant-verification.json) · [界面点击和截图](../../docs/bucket-transfer-verification.json)

地面液态燃料可用同一个 QA JAR 在独立专服检查。复制含 Forge 服务端启动参数的测试实例，安装指定星航主包和 QA JAR，保留锁定的原模组，并在 Java 参数加入 `-Dstarbridge.fuelBucketProbe=true`。以 `nogui` 启动后，探针通过真实桶物品的 `use` 入口操作模拟玩家，并自动关服。在 `logs/latest.log` 查 `FUEL_BUCKET_PROBE PASS` 或 `FAIL`。 [取液回归证据](../../docs/fuel-bucket-verification.json)

给启动器加 `--player-build`，可以让一个新生存玩家用提供的 19 个散件亲自摆放电解机和化学反应器。测试提供平地和可到达站位。确认 21 次操作均在世界画面内完成，并在停服存档读取两台原控制器的 `completeStructure`。[搭建操作与实机结果](../../docs/player-machine-assembly.json)
