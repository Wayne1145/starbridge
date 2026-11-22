# 验证满长度的高级火箭船体

先准备一份已经用 Forge 1.20.1 / 47.4.28 启动过的独立原高级火箭服务端。实例应有已接受的 `eula.txt`、`libraries`、Retrofit、旧运行库、原高级火箭和 LibVulpes。再按[发行构建](../../docs/install-original.md)准备 Starbridge 主包和旧侧船坞附属。使用 JDK 17 和 Python 3，选择一个新输出目录。下面的 PowerShell 例子使用本项目隔离的上游实例。

```powershell
py -3 qa/shipyard-load/run.py --java 'C:\Program Files\Java\jdk-17\bin\java.exe' --profile run-shipyard-bridge-v3 --mod build/libs/starbridge-1.20.1-0.3.0-dev.jar --dock build/libs/starbridge-dock-1.7.10-0.3.0-dev.jar --out run-my-loaded-dock
```

脚本复制两份构建产物，限制专服只监听本机，按 100、200、300 格顺序各建一个全新的两层船存档。可用 `--length 300` 单测一档，`--port` 指定空闲回环端口。每个实例的 `loaded-dock-result.json` 记录原机扫描、原船身份、燃料、实际机器与两层船身、船尾货物和各阶段服务器帧耗时。`loaded-dock-restart.json` 来自同一存档的第二个 JVM。`result-latest.log` 与 `restart-latest.log` 保存两次日志。

测试使用一名 Forge 模拟驾驶员，预置船体、机器供能、原燃料桶和源区块，原组装机的 `buildSpeedMultiplier` 设为 `0.01` 以缩短计时等待。船长用船首的组装机操作，船身中央距组装机可达 151.5 格。两层船为 7 格宽、6 格高，沿全长分布了原引擎、油箱、箱子和工作熔炉。运行边界与测量值见[大船记录](../../docs/loaded-shipyard-verification.json)。

原组装机先确定平台范围，再检查每一格铺面。`qa/shipyard-load/check_floor.py` 在新的回环 `nogui` 服务端给三种长度的平台各挖一格，核对船坞指出的坐标、组装中止及两种原铺面。2026 年 11 月 22 日三档均已通过。开发机可用下列命令重跑。[实测记录](../../docs/launchpad-floor-verification.json)

```powershell
py -3.12 -B qa/shipyard-load/check_floor.py --java 'C:/Program Files/Java/jdk-17/bin/java.exe' --profile run-shipyard-bridge-v3 --mod build/libs/starbridge-1.20.1-0.3.0-dev.jar --dock build/libs/starbridge-dock-1.7.10-0.3.0-dev.jar --out run-my-floor-trial
```
