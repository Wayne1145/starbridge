# 从空背包验证原版首航

隔离专服使用 Forge 47.4.28、JDK 17 和安装清单中的原模组。两名普通生存玩家由各自的完整 Minecraft 客户端操作。客户端在配方书选配方，在工作台和熔炉的实际容器里取物，按游戏的距离和视线要求放置方块、装水和接触生物。Baritone 仅安装在测试客户端，负责步行和采掘，开启 `legitMine`。服务端采集上报的背包、合成事件、角色和存档状态。它不会设置玩家库存、生成方块或传送玩家。

[首批原部件实测](construction-checkpoint.json)已经核对两人的首次空背包、关闭作弊及落盘后的真实原部件。船长采木、挖煤和铁、炼铁、制桶、取水、采砂和砂砾，做出 16 块高级火箭混凝土。后来加入的工程师独立采木，从羊取得三块白羊毛，在工作台做出高级火箭座椅。两名玩家的部件都保存在同一个营地。两人都有夜间死亡和回出生地的记录，角色通过普通复活界面继续，船长曾步行找回掉落的铁桶和工具。完整造船、升空、原月球地牢及返回要以该存档后续完成的操作记录为准。

## 运行专服与渲染客户端

下面的 Windows 客户端操作仅适用于另有独立输入设备的测试机。日常开发工作站使用纯专服、编译和读档，保留 `.tools/no-local-game-clients`。独立图形验收也可以在 GitHub 托管环境进行，例如[甘蔗生长与制作终端](hosted-garden/README.md)。`--runtime` 需要一组已运行过原模组且能离线启动的独立 Forge 实例，包含 `server`、`OrbitCaptain` 和 `OrbitEngineer` 三个目录。每个目录要有实际使用的 Java 参数文件、现代和旧模组、Retrofit 的 `.retrofit` 运行库。客户端还要有经测试的原生库、资源和窗口设置。启动器会重新创建新档，不复制旧实例的区块或角色存档。在相同 `--world` 上重启会沿用已保存的世界，并在 `recordings` 下另存一次运行的日志和安装清单。

从 Baritone 作者的 `v1.10.5` 发行下载 `baritone-api-forge-1.10.5.jar` 到 `build/survival-deps`。启动器校验 SHA-256 为 `6767478e2dd51c6ef278dcf76aabc78f238bc545e8806f82db718b9225ced45e`。检查仓库内 JDK 17 与 Gradle 缓存后构建专用 QA 附属。该附属单独打包，不进入玩家用的 Starbridge 发行包。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
./gradlew.bat --offline -I qa/survival-expedition/fixture.init.gradle reobfSurvivalExpeditionJar
py -3.12 -B qa/survival-expedition/run_windows.py --world run-survival-expedition-a1 --runtime run-gc-pilot-ci44947273292-crew2 --port 26983 --minutes 45 --crew
```

`--actor OrbitEngineer` 或 `--actor OrbitCaptain` 可在同一世界单独运行一个角色，适用于采集阶段。客户端自己持有 `expedition-orders.txt`，按其中的存档进度操作。`expedition-cursor.txt` 仅在观测到当前步骤的预期结果后推进。调试后续操作可在其末尾追加步骤。已经执行过的行要保留，以便记录判读。角色取得自己的未绑定航图终端后，可在露天执行 `readslate`。测试客户端按玩家当前的使用键，点造船图、侧视图和俯视图，再关闭界面。`survival-first-guide.json`、角色事件记录和 `survival-guide-*.png` 用来核对这些操作。公开版的 `readslatecheck` 还会从未绑定终端点击「首航安装检查」，读取界面上的必需和可选组件数，再点「完成」回到刚才的终端。`first-workbench.txt` 串起了采白桦、制作并使用工作台、制作木镐与开采圆石。每次运行的 orders、cursor、两边的事件及玩家 `.dat` 均留在相应运行记录中。

正常停服可以在此试验目录根创建空文件 `stop-after-save`。启动器向该专服发送 `stop`，等待保存后关闭对应后台客户端。为了复核落盘结果，在本次结束后、再次启动世界前执行以下命令。`--capture` 只准创建一次证据副本；后续可去掉该参数直接检查原副本。

```powershell
py -3.12 -B qa/survival-expedition/inspect_checkpoint.py run-survival-expedition-a1/recordings/20261122-051916-2ab6 --out build/first-construction.json
```

## 检验电弧炉耐火砖配方

独立的 `--recipe-only` 实例读取真实配方管理器，把输入放入不连接玩家的临时工作台格。锁定原包的测试里，抗火和力量两种普通药水在 1.7.10 由不同损伤值区分，映射到现代工作台时，原耐火砖配方匹配失败。项目补充等价的配方后，混合两种普通药水和四个红砖块能得到 16 个原耐火砖。错误药水组合仍不能合成。试验的原始结果、构建身份和产物见[耐火砖回归](blast-brick-verification.json)；这些输入不进入生存世界。

## 原组装机的供电

`libvulpes:tile.coalgenerator` 的配方用原电池与熔炉。把原发电机紧贴原组装机，向原燃料槽放入煤。独立专服对上、下和四侧各测了一对原机器，每对组装机在 220 个服务端刻内从 0 升到 8,800 FE。六个面的 Forge 物品自动化接口都没有收下煤。对照试验直接调用了原物品栏的投煤入口，玩家在正常生存存档里制作发电机、打开界面装煤和扫描火箭仍待单独验收。[供电对照](generator-power-verification.json)

在首航终端的「材料与配方」里点击「车间配方」，可以核对原压板器和隔热砖所需的材料。单人测试用工作台配方书取到了压板器，输入来自该测试预置。[工作台和落盘物品](../../docs/workshop-verification.json) · [原压板器两种摆法及错误输入对照](press-layout-verification.json)

船长随后把自采木料搭成 5×5、三格高的营地。正常停服后的区块副本确认了 55 块白桦木板、完整的木门、屋内工作台和储物箱。室外箱另有 10 根白桦原木、两块白羊毛及一块生羊肉。`surface-camp.txt` 记录了在这个种子上每次走位及放置操作。[营地存档复核](surface-camp-verification.json)

工程师随后在同一存档里通过原配方书做成小型压板器，搬回船长建的屋内箱。52 级新阶梯从 Y=64 到 Y=12，沿路插了九支火把。工程师从梯底继续挖红石，再沿梯返营。停服存档里有一台原压板器和七份红石。[从零自制压板器](survival-press-verification.json)

两个白床均已实际放入营地。工程师死亡后徒步取回铁镐和盾牌并存入室外箱。停服区块和玩家的床位记录见[同档存档复核](camp-recovery-verification.json)。

同档船长采到原星际锡矿，在营地自建的熔炉炼出九个锡锭。在工作台手动每格各放一个，做出一个 LibVulpes 原锡块。原压板器、锡块和拉杆已在营地室内箱存妥。[真实工作台取件](../../docs/images/materials/survival-tin-workbench.png) · [角色与箱内材料](survival-tin-verification.json)

专服复核原电弧炉出料时，先编译上述测试附属。为 `run_windows.py` 传入一个新的 `run-survival-expedition-arc-*` 实例和 `--arc-furnace-only --minutes 9`。测试附属在单个保持活动的区块布置原结构，使用原燃煤发电机及原输入、输出口。核对 `original-arc-furnace-probe.json`、关服记录和输出口第四格的原钢锭。[实测记录](../../docs/original-shipyard-recipe-verification.json)
