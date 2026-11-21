# 原船与已联网船员的专服负载验收

此场景使用原组装机实际封存的三艘飞船。每份封存记录包含高级火箭的原火箭编号、舰桥编号、全部方块和机器库存。测试服在新存档中展开三份记录，比对每个箱子和熔炉，然后让完整客户端的船长登上 100 格船，其余联网船员分布在 200 格和 300 格船上。

准备 Forge 1.20.1 / 47.4.28 的专服和两份独立客户端目录，都安装相同的已重混淆 Starbridge、Retrofit、Advanced Rocketry、LibVulpes、Galacticraft、Valkyrien Skies 和 Kotlin for Forge。验收实例还需要 `originalQaJar` 提供历史压力服装备，以及 `connectedFleetQaJar` 提供此场景。用 `-I qa/connected-fleet/fixture.init.gradle reobfConnectedFleetQaJar` 构建测试附属。生产包与测试附属应采用相同 Minecraft 官方到 SRG 映射。

1. 用[载货原火箭测试](../shipyard-load/README.md)产生三份真实封存文件。把每个存档 `data/starbridge-original-ships/` 中的原火箭 `.nbt` 复制到新专服的 `inputs/100.nbt`、`inputs/200.nbt` 和 `inputs/300.nbt`。保留封存文件和来源存档，便于核对舰桥及原火箭编号。
2. 将三个游戏目录并排放置并命名为 `server`、`FleetCaptain`、`Peers`。在 `server` 和 `Peers` 两处放空的 `starbridge-native-fleet.fixture`。给 `Peers` 另外放 `starbridge-fleet.fixture`，给 `FleetCaptain` 单独放 `starbridge-fleet-render.fixture`。
3. 测试服绑定 `127.0.0.1:25891`，`max-players=30`，关闭在线验证与登录资料签名，启用飞行，保持空的管理员名单。用生存模式与和平难度。原记录使用 3 区块服务端视距和模拟距离、4 个 Java 处理器、最高 3,600 MiB 堆和种子 `576618501`。
4. 先启动专服，等 `fleet-peer-request.txt` 出现，再启动两份完整 Forge 客户端。船长使用用户名 `FleetCaptain`，正常联网并渲染游戏。`Peers` 留在标题画面，根据专服请求逐个建立回环 TCP 连接。每个连接走正常 Forge 握手，接收区块、回应心跳和位置确认，每 5 个客户端刻发送一次小幅移动。该进程为这些连接解码网络数据，画面由船长客户端渲染。
5. 看服务端 `fleet-live.json` 的在线人数、采样刻数、各舱产量和航行事件。10、20、30 人分别取 1,200、2,400、12,000 个完整服务端刻。只复现 30 人档时，可另放 `server/fleet-thirty-only.fixture`。到 30 人后，真实船长在星图选原月球、自动驾驶、切换着陆仪并请求着陆，返程通过同样的控件。每端实际抵达时核对原火箭、舰桥、VS 船、乘员和五处货物。
6. 对照 `server/fleet-result.json`、`Peers/peer-counts.json`、船长的 `screenshots/` 和三份 `logs/latest.log`。服务器对每个连接检查回环 TCP、真实服务器玩家、唯一 UUID 与人数。熔炉测试由两艘静泊船上的 22 台原版熔炉共同完成，测试服补充矿石、煤并在输出堆积时取出铁锭。原船体区块保持加载。涉及业务结论时应分别说明连接、客户端渲染、稳态负载、跨维尖峰和测试持续时间。
