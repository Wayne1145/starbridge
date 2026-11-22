# 返航时改选泊位

着陆仪上的中心白框表示已记录的母港。选择附近格子后，服务端按原泊位的高度和船体重心计算新位置。确认降落时会再检查一次空间。

`run_server.py` 使用已接受 EULA 的独立 Forge 专服模板、锁定的 VS 和 Kotlin、新世界与回环端口。测试建一艘三方块船，在母港放黑曜石，先检查占用，再选一个附近的泊位。确认通过后，用生产代码的泊位检查和 VS 跨维调用转移船体。这里没有登录玩家。结果和停服区块检查见[验证记录](../../docs/alternate-home-berth-verification.json)。

```powershell
.\gradlew.bat --offline -I qa/home-berths/fixture.init.gradle test build reobfHomeBerthQaJar
py -3 qa/home-berths/run_server.py new-trial run-concurrent-berths-full3/server build/libs/starbridge-1.20.1-0.3.0-dev.jar build/qa/home-berths/starbridge-home-berths-0.1.0.jar --java 'C:\Program Files\Java\jdk-17\bin\java.exe'
```
