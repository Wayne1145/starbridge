# 完整原包的十连接回归

GitHub 的 `Hosted ten-player fleet` 工作流在一台 Ubuntu runner 上启动 Forge 专服和一个连接客户端。客户端使用独立的 Xvfb 显示器，从标题界面建立 `FleetQA01` 到 `FleetQA10` 十条真实 Forge TCP 连接。服务器只监听回环地址。每个身份都有自己的游戏资料、握手、移动包、区块和心跳。全部身份使用生存模式，权限列表为空。

工作流从同一修订的 `Build and test` 取主包和旧版船坞伴随包，并校验发行包摘要。两端从作者发行渠道安装锁定的九项依赖，包括高级火箭、星际及两个扩展、VS 和 CC:Tweaked。原版客户端缓存与专服缓存分别生成。测试附属再核对实际加载的原组装机、发电机、电脑和物理模组，并从服务器星图读取原月球、ExtraPlanets 金星和 GalaxySpace 谷神星的世界键。[安装清单](../../pack/original-spaceflight.lock.json)

测试预置长 100、200、300 格，宽 8、高 4 格的三个固定舱体，以及炉料、能量、货箱和已经开始的自动航线。十个账号均开始移动后，服务器逐刻观察同一批身份至少 75 秒。首个完整分钟须十人在线并移动、三船持续巡航、熔炉有产出、箱中分别有 3、5、7 颗钻石。停服后从十份角色存档和三个真实箱子复核人员、位置及货物。计时从十人全部就绪开始，报告附实际 TPS、95 百分位和最慢一刻。

在拉取请求中变动工作流、船体、锁定清单或测试实现会触发回归。`linux-ten-fleet-<run>-<attempt>` 产物包含两端安装摘要、实测期间的客户端计数、服务端逐分钟记录、停服读档结果和诊断日志末尾。离线测试可在 Windows 上用 `py -3 -B -m unittest discover -s qa/linux-fleet -p 'test_*.py' -v` 运行。要重放成功产物中的装载版本和测量，请保留 `installed.json`、`measured-peers.json`、`soak-fixture.json`、`soak-windows.jsonl`、`linux-witness.json` 和 `evidence.json`。

十条本地连接共享一个渲染进程。这个场景验证同一完整安装的握手、船舱活动和停服数据。300×19×8 格双层船载人跨维的验证见[宽船往返](../connected-fleet/wide300-verification.json)。要求同一进程连续 48 小时的三档验收见[专服长测](../concurrent-orbits/README.md)。2026-11-22 查询本仓库自托管 runner 列表返回 0。GitHub 托管作业上限为六小时，正式长测需要一台允许独立连续运行 48 小时的测试主机。[GitHub 运行时限](https://docs.github.com/en/actions/reference/limits)

手动运行托管工作流会单独测首档十人连续两小时。它核对同一专服进程的逐分钟记录、全部玩家的移动和最终关服存档。完整发行验收仍使用下方同一进程连续 48 小时的三个档位。

## 在独立 Linux 测试机连续运行 48 小时

专服和一个可维持 30 条游戏连接的 Forge 客户端在同一台已获准使用的 Linux x86_64 主机上运行。游戏服监听 `127.0.0.1:26750`，客户端使用程序创建的 Xvfb 显示器。安装器在测试机上从上游下载原包并校验哈希，随后创建新世界。角色和存档保存在测试机的实例目录中。

在该机器的无图形终端准备 Java 17、Python 3.11 或更高版本、Git、能读取本仓库 Actions 产物的 `gh`、Xvfb、xauth 和 Mesa 软件渲染库。采用支持这套长测的仓库修订，并确认对应的 `Build and test` 发行包仍可下载。在仓库根目录构建测试附属。下面两条命令在独立测试机上运行。可在 `tmux` 会话内启动，以便断开管理终端后继续采样。

```sh
./gradlew -I qa/linux-fleet/fixture.init.gradle reobfLinuxFleetQaJar --max-workers=2 --console=plain
python3 -B qa/linux-fleet/long_soak.py --dedicated-host "$(hostname)" --root "$HOME/starbridge-48h-01"
```

脚本验证主机名、Java 版本和本机客户端停用标记。Windows、WSL 和工作站的图形会话会在安装或启动前被拦截。程序从当前修订对应的发布包取主包与船坞伴随包，以同一份锁定清单安装两端的九项依赖。游戏数据写入一个全新目录。可用 `--build-run` 指定当前提交对应的构建编号。每个 JVM 的堆上限为 3 GiB，运行期间至少保留 4 GiB 可用磁盘空间。

10、20、30 人三个阶段分别需要整整 2、6、40 小时的满员移动记录。扩容时的连接等待单独计时，所以总运行时间可能略长于 48 小时。服务端记录每分钟的在线及移动名单，已经参加的账号须保持原 TCP 连接。三艘船每分钟持续向月球接近，每小时分别经历巡航、制动和重新巡航。所有窗口、进程号和原版世界提供方均可从实例目录复核。

运行时可执行 `python3 -B qa/concurrent-orbits/check_soak.py "$HOME/starbridge-48h-01"` 查看已测时长。在实例根创建 `STOP` 会触发正常停机，结果记为中断。结束后，`long-result.json` 同时记录完整验收结果、每档满员时长、30 人停服位置和三箱钻石。`soak-final-audit.json` 和 `soak-final-evidence.zip` 保留逐分钟证据、安装摘要、连接计数及停服记录。原始存档留在 `server/world`。

GitHub 托管的十连接短测覆盖首个完整分钟。连续 48 小时实测仍待可独立运行两天的测试机。2026 年 11 月 22 日查询时，本仓库登记的自托管 runner 数量为零。不同 GitHub 托管作业各有自己的回环地址，无法接续这台回环专服的既有 TCP 连接。[GitHub Actions 作业时限](https://docs.github.com/en/actions/reference/limits)
