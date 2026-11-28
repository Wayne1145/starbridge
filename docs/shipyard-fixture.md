# 复现原组装机的单人、双人专服验收

准备独立的 Forge 1.20.1 / 47.4.28 服务端和对应客户端，使用 JDK 17。在所有实例中安装同一份 Starbridge 开发 JAR 与 Retrofit 0.6.2-beta，将高级火箭和 LibVulpes 的 1.7.10 原包放在每个实例的 `retrofit/mods`。三个上游 JAR 的文件名和校验值在[验证记录](shipyard-verification.json)。客户端需要完成 Retrofit 的旧版客户端库与资源准备。

1. 用 `./scripts/dev.ps1 -Task build` 构建。在每个参与实例的游戏目录放一个空文件 `starbridge-disposable-shipyard.fixture`。用一个全新存档，避免沿用上次测试放过的方块。启用场景后，服务端会在主世界 `(19, 191, 17)` 放置机器、船和货物。
2. 服务端设 `server-ip=127.0.0.1`、`server-port=25594`、`online-mode=false`、`spawn-protection=0`、`gamemode=survival`、`allow-flight=true`、`enforce-secure-profile=false`。船长和工程师都不授予 OP。测试服限本机连接。
3. 专服和客户端的 **JVM 参数**都加入 `-Dstarbridge.shipyardGuiFixture=true`。双人再给三者加入 `-Dstarbridge.shipyardPair=true`。船长的测试账号名为 `ShipyardPilot`，工程师为 `ShipyardEngineer`，每个客户端使用独立游戏目录。只验证船长时用一台客户端并省略双人参数。
4. 启动专服，等日志出现 `Done`，再使用普通客户端启动到标题画面。测试驱动经正常多人连接进入服务端，右击游戏内组装机并点击实际控件。请保留原生窗口及渲染，可以在独立桌面运行。客户端会使用账号名识别两种角色。
5. 在专服游戏目录查 `shipyard-gui-server.txt`，每个客户端目录查 `shipyard-gui-client.txt`。核对服务器和所有客户端均写出 `PASS`，再对照各自的 `logs/latest.log` 及 `screenshots/`。单人记录覆盖扫描到船舱星图。双人还核对原火箭升空时的两处座位、舱内位置、工程师行走和箱子内外的钻石数。测试预置了船体、机器能源和燃料桶，前期生存生产需另测。

仅复现箱子、熔炉和飞行核心的序列化回归时，可在单独的专服存档设置 `-Dstarbridge.shipyardFixture=true` 并创建同名标记文件。该场景使用 Forge FakePlayer，读 `shipyard-fixture-result.txt`。真实客户端和模拟场景应使用不同的实例目录和日志。

要在同一个存档、同一艘原火箭上接着测航行，专服和两端同时启用 `-Dstarbridge.shipyardLinked=true`。随后服务端会保留已验证的固定舱和两名船员，继续运行。可用 `-Dstarbridge.shipyardPilot=OrbitCaptain`、`-Dstarbridge.shipyardEngineer=OrbitEngineer` 指定同一批测试账号，客户端可用 `-Dstarbridge.shipyardGuiPort=25628` 指定回环端口。等三份船坞结果都为 PASS 后，由下一段客户端操作选择航线。

复现乘客掉线时，在独立的双人场景同时设置 `-Dstarbridge.shipyardReconnect=true`。工程师的客户端会在实际升空时断开连接，再通过正常多人连接重进同一存档。服务端在原火箭移交后明确记录船长在舱、工程师仍离线，然后核对重连者的座位、取货和库存。

复现图形修船时，专服和船长客户端同时启用 `-Dstarbridge.shipyardRepair=true`。专服取下示例船的原导航电脑并把同种零件放入船长背包。客户端要看到原机错误、使用「零件位置」、正常放回零件后再扫描、加注和发射。读 `docs/shipyard-verification.json` 的 `real_client_repair`。
