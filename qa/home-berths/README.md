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

本机离线检查用 `py -3.12 -B qa/home-berths/test_remote.py -v`。Java 编译检查需要同时传入 `qa/home-berths/fixture.init.gradle` 和 `qa/home-berths/remote.init.gradle`。以下回放使用已经完成的云端运行。


2026-11-22 的托管运行 `44982848366` 已完成。船长两次点同一个备选泊位，第一次确认时遭拒，障碍移除后第二次成功。船长和船上木桶里的七颗钻石抵达原母港东 63、北 37 格处。着陆收费 12,000 FE。六张原图、玩家输入和停服存档核对摘要已随仓库保存。[实测记录](../../docs/remote-home-port-verification.json) · [被拒时的着陆仪](../../docs/images/home-port/refusal.png) · [恢复净空后的着陆仪](../../docs/images/home-port/retry.png)

使用装有 Pillow 的 Python，在仓库根运行以下命令即可从归档回放。程序核对截图散列和十字、准星的完整像素轮廓，再将按键、地图选点、服务器勘测回执和保存结果逐项对应。

```sh
python -B qa/home-berths/replay_remote.py docs/evidence/remote-home-port/44982848366.zip --sha256 ecdef9d654e905122c253aa45139afc004de912e542be18c34a6acd629a0d0b3
```

300 × 19 × 8 原船的跨维测量使用已停服的原船副本。`run_wide_capture.py` 加 `--transfer` 后，纯专服将同一艘船从主世界送到 Galacticraft 月球，并分别计时泊位规划、原生传送及两端的货物检查。关服后可以独立复核原区块和物品槽。

```sh
python -B qa/home-berths/audit_wide_transfer.py docs/evidence/hull-capture/wide-transfer-worlds.zip
```

复核模拟船长正常确认后，运行 `python -B qa/home-berths/audit_wide_approach.py docs/evidence/hull-capture/confirmed-approach.zip`。归档保留两个关服世界相关区域、船只、角色、航程及关服日志摘要。原试验在 `run_wide_capture.py` 选择 `--approach`，预设了近地位置，使用正常的服务端勘测和确认入口。

两艘 300 × 19 × 8 船的并发着陆用 `run_wide_capture.py` 的 `--parallel-approach`。它复制已审计的关服原船，使用第二个不同船号、船长和货箱库存，从同一个服务端刻请求两个相距 512 格的原月球泊位。运行后用 `audit_parallel_approach.py` 传入原专服目录和新实例目录，逐格比较两艘落盘船体、两份货物和收费票据。[实测结果与原始计时](../../docs/parallel-wide-approach-verification.json)

`--parallel-survey` 复用原船与副本，让两名模拟船长走正常服务端勘测、保持地图查询并在同一刻确认。复核命令与直接交接相同，另核对两次组装分刻执行及每处地表的区块保留。

`run_wide_capture.py` 加 `--storage-recovery` 可在原 300 格船的校验副本上测试空船位取消和重启。两次纯专服用 `nogui` 启动，无玩家或图形客户端。脚本读取两次关服的 VS 船表和 Starbridge 船位登记，并将结果写到 `storage-recovery-audit.json`。
