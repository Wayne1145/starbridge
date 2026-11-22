# 多舰泊位与长时间在线验收

两项测试使用独立的 Forge 1.20.1 / 47.4.28 存档和 Java 17。测试 JAR 只装在临时实例。锁定发行包及其两端的现代和原包 JAR，按[原包安装指南](../../docs/install-original.md)安装 Galacticraft、高级火箭、LibVulpes、Retrofit、Kotlin for Forge 与 Valkyrien Skies。两个场景实际使用的哈希、日期和预置见[验证记录](../../docs/concurrent-orbits-verification.json)。

在仓库根用 `./gradlew.bat -I qa/concurrent-orbits/fixture.init.gradle reobfConcurrentOrbitQaJar` 构建测试附属，产物在 `build/qa/concurrent-orbits/`。本机已有对应命名库与映射缓存时，也可用 `py -3 qa/concurrent-orbits/fixture_build.py build/concurrent-orbits/qa-local` 编译。测试包已包含建立网络连接所需的舰队测试类，同一实例只装一份 `starbridgefleetqa`。

## 两位船长选泊位

1. 在同一新目录下准备 `server`、`OrbitCaptain`、`OrbitEngineer`。三端使用相同正式包、泊位测试包，以及[原航程测试附属](../original-flight/README.md)提供的供氧装备预置。三个工作目录都放空文件 `starbridge-concurrent-berths.fixture`。两名客户端分别使用同名的本地身份和 `zh_cn`。服务端使用 `127.0.0.1:26751`、`online-mode=false`、`enforce-secure-profile=false`、`spawn-protection=0`、`allow-flight=true`、`max-players=2`、`gamemode=survival`、`difficulty=peaceful`、`level-seed=576618501`、视距和模拟距离 4。保持空 OP 名单。
2. 先开专服，再让两套图形客户端连接到该地址。每轮预置两艘含原引擎、胸甲、氧气和相互区别的库存的小船。两名船长从舰桥打开星图，选择月球，进入巡航，在着陆仪地图选泊位，制动到停止后各自点击「开始降落」。测试附属暂存这两个客户端请求，再在同一服务端刻进入正式着陆处理程序。
3. 第一轮选同一地点，观察其中一位船长的地图显示「泊位被占用」，降落按钮停用。留在航行中的船应保持原库存、收据和余额。船长直接点击地图重选，再确认降落。第二轮两艘新船同时选择不同泊位并确认。测试服保存每次点击到达的刻、实际处理的刻、船身身份、能源、货物、目的地与在船人员，两台客户端分别留存按键和截图。
4. 待 `server/berth-result.json` 落盘及专服正常停机，运行 `py -3 qa/concurrent-orbits/audit_berths.py <实例根目录>`。第一次冲突、改选或第二轮任一步失败都要连同 `server/berth-live.json`（如已生成）、`berth-rounds.json`、两侧 `berth-input.log`、`screenshots`、原日志和崩溃文件保留。同刻分派用于精确复现竞态，这里没有测试模组从原机造船或同一玩家手动操控两台客户端。

着陆仪的图形回归会等到对应界面完成一帧渲染，再抓取占用和改选的两张截图。Windows 下运行 `./qa/concurrent-orbits/check_marker.ps1 <实例根目录>` 会读取大地图上的像素，检查占用叉号有实心中心、可用准星中央留空。专服的 `OCCUPIED` 值与这组画面分别核对。

## 两个扩展世界首次载入

在上述三端装齐[原包锁定清单](../../docs/install-original.md)中的 ExtraPlanets 与 GalaxySpace，使用相同的 `config/ExtraPlanets.cfg` 及原配置。三个目录再各放一个 `starbridge-split-worlds.fixture`。单独构建与安装原航程测试附属，带有该标记时它会为测试人员穿好按原化学反应器配方做过气密处理的 GalaxySpace 套装。对应场景使用新世界、两个具名的渲染客户端和两艘带箱子与炉料的独立小船。

前两轮是同一月球世界的竞争与独立泊位。第三轮使用两艘新船，预置相应飞船科技级别，让客户端按星图翻页选择 ExtraPlanets 金星和 GalaxySpace 谷神星，各自在地图上选一个此前未访问的区块。服务器只读检查该区块的驻留状态和区域文件扇区表，在第一次实际勘测请求、原区块载入、净空回复、同刻确认、玩家切换维度、船体交付时用单调时钟采样。两名船长同时在两颗星球保持 200 个完整刻后才停服。`audit_berths.py` 另读停服后的存档，核对两位角色的维度与生命、各原船落盘的货物和收据、空航程账本以及三端实际安装的扩展 JAR。首次勘测到地形图可用的时长和确认降落后交付的时长分开列出。

## 三舰连续 48 小时

标准布置为同一目录下的 `server` 和 `Peers`。服务端只加 `starbridge-orbit-soak.fixture`，客户端只加 `starbridge-soak-peers.fixture`，均为空文件。现代两端安装同一测试 JAR。专服使用 `127.0.0.1:26750`、新世界、生存、和平、30 个位置、视距与模拟距离 3，保留上述本机离线测试选项。专服先启动，客户端在标题界面建立独立的 Forge TCP 连接。

在配有独立输入环境的专用 Windows 测试机上，准备好[舰队两端](../connected-fleet/orbital-run.md)后可用以下入口生成隔离目录并采样。开发工作站保留 `.tools/no-local-game-clients`，只做纯专服和读档。`--reference` 指向含 `server`、`Peers`、已同意的测试服 EULA、安装缓存、客户端原生库和启动参数的模板根目录。以下使用 PowerShell，从仓库根执行。

```powershell
py -3 qa/concurrent-orbits/prepare_soak.py run-concurrent-soak-try1 build/ci-44947273292/starbridge-0.3.0-dev.zip build/qa/concurrent-orbits/starbridge-concurrent-qa.jar --reference run-native-fleet-crewdeck1
py -3 qa/concurrent-orbits/soak_host.py run-concurrent-soak-try1
```

在另一个终端执行 `py -3 qa/concurrent-orbits/check_soak.py run-concurrent-soak-try1` 可读取当前实际时长；加 `--watch` 则每半分钟复核，并每十个完整采样窗口保存一次摘要。Windows 上 `soak_host.py` 在后台桌面启动并只管理它自己创建的 JVM。需要提前结束时在该实例根创建空文件 `STOP`，监控器会正常停服并将该次记录为中断。重测需使用新的实例名，先前在线时间不会并入。

计时从十个角色已登录且均有移动开始，服务端每个约一分钟的观察窗口保存刻数、吞吐、tick 耗时分布、最少和最多在线人数、移动账号数、货物、熔炉产出及三条航线状态。前两小时目标十人，之后六小时二十人，其余四十小时三十人。升档期间在线人数单列。一台网络客户端进程承载多条独立游戏连接，固定舱体预置，测试程序定时补充燃料、石料和能源，并对三艘船执行巡航、制动和恢复。物理化降落、不同外部网络和每个账号的图形帧率需用相应的场景检验。

需要存下一段可独立核对的原始窗口时，运行 `py -3 qa/concurrent-orbits/record_soak.py run-concurrent-soak-try1 --snapshot <归档.zip>`。在单独的终端启动 `py -3 qa/concurrent-orbits/record_soak.py run-concurrent-soak-try1 --on-finish` 后，正常停服或中断都会在实例内产生 `soak-final-audit.json` 及 `soak-final-evidence.zip`。中断或缺少完整时长一律记录失败。最终报告也检查正常保存、两端实际 JAR 的 SHA-256 和每档的满员时长。

全扩展持续服可以在准备命令上附加 `--original-template run-gc-pilot-ci44947273292-crew2 --modern-jar <已验证的主包.jar> --modern-sha256 <SHA-256>`。模板中的两个原扩展、前置和共存配置将同时装入服务端及连接客户端。需要错开渲染进程时，监控器的 `--peer-after <另一个实例/launch-result.json>` 会等该实例关闭后再开连接客户端，实际在线计时始于十个身份均开始移动。
