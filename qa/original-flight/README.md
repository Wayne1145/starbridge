# 用两台客户端复测原月球航程与船舱供氧

这组测试使用两个普通生存身份 `OrbitCaptain` 和 `OrbitEngineer`、一个 Forge 47.4.28 专服，以及两套完整客户端。请用 JDK 17 和独立存档，按[原包安装指南](../../docs/original-runtime.md)配置 Galacticraft 3.0.12.504、Advanced Rocketry 1.4.0-32、LibVulpes、Retrofit 0.6.2-beta 和 Valkyrien Skies 2.4.11。每个实例都安装同一次构建的 Starbridge。测试附属单独生成。

```powershell
./gradlew.bat --offline -I qa/original-flight/fixture.init.gradle reobfJar verifyOriginalQaJar
```

把 `build/qa/original-flight/starbridge-flight-fixture-1.0.0.jar` 加入三个临时实例的现代 `mods` 文件夹。三个目录分别命名为 `server`、`OrbitCaptain` 和 `OrbitEngineer`，放在同一父目录下。测试附属通过该目录记录操作与读数。两台客户端设为窗口渲染。专服使用 `server-ip=127.0.0.1`，测试身份不需要 OP。设 `gamemode=survival`、`difficulty=normal`、`spawn-protection=0`、`allow-flight=true`、`online-mode=false`、`enforce-secure-profile=false`，新世界种子为 `576618501`。

每个实例放一个空的 `starbridge-original-crew.fixture`，客户端的 `qa-server.txt` 填入实际本地地址和端口。先开服再开两套客户端。保留服务端 `logs/latest.log`、`flight-result.json`、可能的 `crash-reports`、两端的输入记录与截图。

| 场景 | 实例设置和实际观察 |
| --- | --- |
| 气密舱与原生真空 | 三个实例都添加 `starbridge-oxygen.fixture`。测试服在月球建有氧的玻璃舱和露天平台。船长右击供氧器，点选补气，另一人留在舱外。测试服定时开放舱壁后重新封舱。检查 `ventMenuRefilledFromInventory`、密封期间的健康值与原真空迟缓、开舱损伤、复压时的健康变化。 |
| 小船载货返回 | 单独使用 `starbridge-original-crew.fixture`。测试服预置有原火箭发动机的方块船、船员装备和初始能源。两程由船长在星图和着陆仪上点击。核对两程的独立收据、能源、船员、方块实体库存和清空的航程记录。 |
| 组装、原地牢、战利品和重连 | 三个实例添加 `starbridge-joined.fixture` 和 `starbridge-disposable-shipyard.fixture`。三端同时启用下方 JVM 选项。开服前将同种子自然生成、尚未击败首领的月球 `r.0.0.mca` 放在新世界 `dimensions/retrofit/legacy_dim_-28/region` 下。生成原始样本的方法见[原地牢种子对照](../retrofit-lunar-seed/README.md)。 |

```text
-Dstarbridge.shipyardGuiFixture=true -Dstarbridge.shipyardPair=true -Dstarbridge.shipyardLinked=true
-Dstarbridge.shipyardPilot=OrbitCaptain -Dstarbridge.shipyardEngineer=OrbitEngineer -Dstarbridge.shipyardGuiPort=25628
```

第三种测试先等待三份船坞实测记录。两名角色亲自使用组装机、推进剂桶和两个原座位，工程师从原船箱中取出一颗钻石。测试服发放装备和舰桥能量，驾驶员在星图选择原月球。测试控制器记录同一艘船和两人，在自然首领房、原宝箱和月球甲板之间帮助工程师定位。战斗、钥匙拾取、用钥匙开箱、将战利品转入船舱都由客户端完成。返程中断开工程师的连接，等待正常重新登录同一舱后再允许船长点击着陆。此场景的记录应标明位置预置与材料来源。

服务端的 JSON 判定涵盖玩法数据。还要核对原始日志中是否发生看门狗超时或崩溃。[原始结果索引](../../docs/original-crew-verification.json)

## 返港收费时重启专服

在前述两人联飞的三个独立测试实例中加入 `starbridge-crash-restart.fixture`。开服前保持相同的种子、船坞设置和未使用的存档。使用下面的程序代为启动本次测试的专服，客户端仍照常连接。`launch.args` 必须包含上文的联飞选项与正确的本机游戏类路径。程序在同一存档启动第二个 Java 进程时会关闭船坞预置。

```powershell
py -3 qa/original-flight/restart_server.py --server D:/lab/starbridge-trial/server --java 'C:/Program Files/Java/jdk-17/bin/java.exe'
```

第一次返港时会先写入两端船体区块和实际船舱上的扣费收据。就在正式航程账本准备确认、两名船员尚未记为交付时，测试附属写出信号并等待。外部程序核实信号所属专服的进程号，终止这个进程，留存关闭时的原文件，然后用原存档重启。游戏客户端重新连接。检查两人的身份、存活状态、甲板位置、货箱的物品与格数、熔炉内原料与成品总数、同一艘船的标识，以及已有收费收据和航程账本。状态需要在后续 100 个游戏刻保持一致。

用以下命令独立读取两次启动之间保存下来的区块和航程记录。`recovery-result.json` 是第二次开服后实际观察到的结果。两个文件应一起核对。

```powershell
py -3 qa/original-flight/inspect_restart.py D:/lab/starbridge-trial/server/restart-evidence D:/lab/starbridge-trial/server/disk-recovery-observation.json
```

落点反馈对照使用一个新的预置小船场景，在全部实例加入 `starbridge-approach.fixture`。试验月球将边界设为以零点为中心、宽 800 格。驾驶员在着陆仪选择边界外的坐标，收到服务器反馈后在同一张图改选零点。`landing-result.json` 记录有效着陆时所有船体方块中心底面相对天然地形的最小距离，`OrbitCaptain/rejected-site.json` 和截图记录被拒绝时驾驶员看见的内容。
