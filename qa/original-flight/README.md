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

用以下命令独立读取两次启动之间保存下来的区块和航程记录。`recovery-result.json` 是第二次开服后实际观察到的结果。两个文件应一起核对。离线取证必须在旧进程退出、新进程打开存档前进行。按航程账本中的整船边界计算需留存的区域文件，覆盖出发地和目的地，然后逐区块对照实际扣费编号。若任一文件缺失，分别记录已保留的证据和游戏内结果。旧版存档没有逐区块印记，应用的验证规则不同。

```powershell
py -3 qa/original-flight/inspect_restart.py D:/lab/starbridge-trial/server/restart-evidence D:/lab/starbridge-trial/server/disk-recovery-observation.json
```

落点反馈对照使用一个新的预置小船场景，在全部实例加入 `starbridge-approach.fixture`。试验月球将边界设为以零点为中心、宽 800 格。驾驶员先选边界外的坐标，再选零点。零点显示净空后，用例在测出的地表上放一个盔甲架。玩家点「开始降落」时应看到泊位被占用，随后点「复飞」回到驾驶视角，点巡航后改选 `(64,-64)`。`landing-result.json` 记录船底实际离月面多远，取样时在目的地逐列读取方块碰撞体。客户端的 `rejected-site.json`、`occupied-site.json`、`climb-witness.json`、`safe-site.json` 和截图分别记录各个界面动作与结果。

## 全扩展的辐射与原装备

使用前述双人生存联飞场景，双方安装同一组 Galacticraft、Advanced Rocketry、ExtraPlanets 和 GalaxySpace 原包，并保持 GalaxySpace 的太阳辐射开启。已安装 GalaxySpace 时，用例检查其熔合机的四件宇航服配方，再从高级火箭化学反应器的已登记配方取得四件气密成品。套装由用例提供，原面罩、供氧装置和两只重型氧气瓶实际位于 Galacticraft 的额外装备栏。

在独立对照专服目录放置空文件 `starbridge-ar-suit-control.fixture` 会改用高级火箭自己的全套压力服和满气罐。`server/crew-health.jsonl` 记录原侧事件的伤害源、宿主实际扣血、角色生命、护甲和原氧气读数。该记录器不取消事件或改写伤害。跑完对照与防护航程后，使用 `py -3.12 qa/original-flight/audit_solar.py <对照实例> <防护实例>` 核对伤害匹配、原配方、玩家返程、货物、账单、三端哈希和停服。可复查的数据在 [全扩展联飞记录](../../docs/original-radiation-verification.json)。

## 两名玩家各自打开原版氧气装备栏

另建一组三个实例，沿用上文的两个角色名、Java 17 和相同的 Forge 配置。三端放置 `starbridge-equipment-ui.fixture`，都不要放置 `starbridge-original-crew.fixture` 或 `starbridge-joined.fixture`。将同一份主包与本目录构建的测试附属安装到三端。`qa-server.txt` 填本次专服的地址，主机可用 `--quickPlayMultiplayer` 让两套客户端接入。

服务端给船长准备一座属于他的舰桥，并给两名普通生存玩家各发一个未绑定航图终端、一副星际面罩、一套供氧设备和两只满氧气瓶。船长用终端右击舰桥，点月球、月面出舱装备和「打开氧气装备栏」。工程师没有船，直接使用终端，在首航图解点同一按钮。两人分别在 Galacticraft 原容器中用鼠标将四件装备从快捷栏移进自己的原槽。船长完成后，专服检查工程师的装备仍在背包，再允许工程师开始操作。

另外两种场景用全新存档。移除三端的 Galacticraft 原包并添加 `starbridge-equipment-missing.fixture`，检查玩家收到「当前世界未安装星际原版供氧装备。」且仍留在原页。要验证原模组已安装但当前无法打开容器，保留原包，改为添加 `starbridge-equipment-no-handler.fixture`。测试附属仅在该专服运行期间让原 GUI 5 的处理器返回空，其他请求仍交给原模组。两名玩家都应看到「星际装备栏暂时无法打开，请稍后重试。」并保留四件装备。各场景请保留专服与客户端的 `equipment-ui-result.json`、`captain-checked.json`（穿戴成功场景）以及截图。实测环境、哈希和差异见[氧气装备栏验证](../../docs/original-oxygen-equipment.json)。

## 从月球甲板步行勘察

三个全新实例都装同一份原包组合、生产包和测试附属。只放 `starbridge-lunar-trail.fixture` 和 `starbridge-disposable-shipyard.fixture` 两个场景标记，使用前述船坞 JVM 选项和船员姓名，端口填各自的回环专服端口。`server.properties` 中的 `level-seed=` 留空。保持新档，月球区块由本次专服自然生成。测试服准备样船、航程能源、装备和终端。两位生存船员操作原组装机、加注、乘坐各自的座位。船长点星图和着陆仪。工程师在原月球甲板潜行使用航图终端，从勘察图标记船位和查看地表深坑，并在地面按移动键。

三端保留 `shipyard-gui-*.txt` 和 `flight-input.log`。工程师端的 `lunar-walk-live.json` 记实际经过的位置，发现可见的原地牢砖后才写 `observed-entrance.json`。专服的 `lunar-trail-live.json` 记录位置、血量和每刻位移，`lunar-trail-result.json` 分别记录登船返航和失败。单独核对船位、洞口、返回甲板和返回母港。此场景里的洞口记录不能作为击败 Boss 或获得战利品的证据。
