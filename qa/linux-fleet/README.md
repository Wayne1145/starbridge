# 完整原包的十连接回归

GitHub 的 `Hosted ten-player fleet` 工作流在一台 Ubuntu runner 上启动 Forge 专服和一个连接客户端。客户端使用独立的 Xvfb 显示器，从标题界面建立 `FleetQA01` 到 `FleetQA10` 十条真实 Forge TCP 连接。服务器只监听回环地址。每个身份都有自己的游戏资料、握手、移动包、区块和心跳。全部身份使用生存模式，权限列表为空。

工作流从同一修订的 `Build and test` 取主包和旧版船坞伴随包，并校验发行包摘要。两端从作者发行渠道安装锁定的九项依赖，包括高级火箭、星际及两个扩展、VS 和 CC:Tweaked。原版客户端缓存与专服缓存分别生成。测试附属再核对实际加载的原组装机、发电机、电脑和物理模组，并从服务器星图读取原月球、ExtraPlanets 金星和 GalaxySpace 谷神星的世界键。[安装清单](../../pack/original-spaceflight.lock.json)

测试预置长 100、200、300 格，宽 8、高 4 格的三个固定舱体，以及炉料、能量、货箱和已经开始的自动航线。十个账号均开始移动后，服务器逐刻观察同一批身份至少 75 秒。首个完整分钟须十人在线并移动、三船持续巡航、熔炉有产出、箱中分别有 3、5、7 颗钻石。停服后从十份角色存档和三个真实箱子复核人员、位置及货物。计时从十人全部就绪开始，报告附实际 TPS、95 百分位和最慢一刻。

在拉取请求中变动工作流、船体、锁定清单或测试实现会触发回归。`linux-ten-fleet-<run>-<attempt>` 产物包含两端安装摘要、实测期间的客户端计数、服务端逐分钟记录、停服读档结果和诊断日志末尾。离线测试可在 Windows 上用 `py -3 -B -m unittest discover -s qa/linux-fleet -p 'test_*.py' -v` 运行。要重放成功产物中的装载版本和测量，请保留 `installed.json`、`measured-peers.json`、`soak-fixture.json`、`soak-windows.jsonl`、`linux-witness.json` 和 `evidence.json`。

十条本地连接共享一个渲染进程。这个场景验证同一完整安装的握手、船舱活动和停服数据。300×19×8 格双层船载人跨维的验证见[宽船往返](../connected-fleet/wide300-verification.json)。要求同一进程连续 48 小时的三档验收见[专服长测](../concurrent-orbits/README.md)。2026-11-22 查询本仓库自托管 runner 列表返回 0。GitHub 托管作业上限为六小时，正式长测需要一台允许独立连续运行 48 小时的测试主机。[GitHub 运行时限](https://docs.github.com/en/actions/reference/limits)
