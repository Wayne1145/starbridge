# 返航时改选泊位

着陆仪上的中心白框表示已记录的母港。选择附近格子后，服务端按原泊位的高度和船体重心计算新位置。确认降落时会再检查一次空间。

`run_server.py` 使用已接受 EULA 的独立 Forge 专服模板、锁定的 VS 和 Kotlin、新世界与回环端口。测试建一艘三方块船，在母港放黑曜石，先检查占用，再选一个附近的泊位。确认通过后，用生产代码的泊位检查和 VS 跨维调用转移船体。这里没有登录玩家。结果和停服区块检查见[验证记录](../../docs/alternate-home-berth-verification.json)。

```powershell
.\gradlew.bat --offline -I qa/home-berths/fixture.init.gradle test build reobfHomeBerthQaJar
py -3 qa/home-berths/run_server.py new-trial run-concurrent-berths-full3/server build/libs/starbridge-1.20.1-0.3.0-dev.jar build/qa/home-berths/starbridge-home-berths-0.1.0.jar --java 'C:\Program Files\Java\jdk-17\bin\java.exe'
```

## 在独立测试机验收返航地图

[Remote home-port map](../../.github/workflows/remote-home-ui.yml) 在 GitHub 托管的 Ubuntu 测试机上运行一个中文 Forge 客户端和它的单人生存服务器。客户端从同一提交的公开开发包加载 Starbridge，另装锁定版本的 Galacticraft、Retrofit、Valkyrien Skies 和 Kotlin for Forge。

船长在原月球上使用已配对的航图终端，在星图点母港和自动巡航，再从驾驶席打开着陆仪。原母港有一块黑曜石。图应显示橙色叉并禁用降落。船长选附近的净空泊位，点中心白框回到原母港，再点回刚才的位置。测试分别保存这三次选择的画面。

当备选泊位显示净空时，夹具在该位置放一块金块。玩家在原来显示绿色的图上确认降落，生产代码应重新勘测并拒绝。金块移除后，船长从同一张图再次确认，船、船长和七颗钻石应抵达备选泊位。夹具只读记录两次最终勘测的结果。停服后从玩家、船体、货舱和航程存档核对船主、船号、船票、货物和原母港坐标。

本场景预置一段 4×2×3 的固定船舱、原火箭身份、能源、母港坐标、终端配对、货物及月面保护药效。普通玩家身份、关闭作弊和实际着陆受检查。自行造船、采集物资、生命保障、多人操作与真人初次发现入口属于其他场景。六张着陆仪截图、输入记录、两端结果及存档摘要随 `home-port-map-…` 产物保留七天。

本机离线检查用 `py -3.12 -B qa/home-berths/test_remote.py -v`。Java 编译检查需要同时传入 `qa/home-berths/fixture.init.gradle` 和 `qa/home-berths/remote.init.gradle`。只有托管工作流完成后，才可以记录此次返航图的客户端实测结论。
