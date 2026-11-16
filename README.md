# Starbridge / 星航

面向 **Minecraft Java 1.20.1 / Forge** 的太空探索与可编程飞船模组。当前是开发中的原创原型，不是 Galacticraft、ExtraPlanets 或 GalaxySpace 的移植发行版。

目标包括：玩家自建的大型移动飞船、船内机器与多人活动、行星探索、游戏内电脑和可选 AI 控制。**100–300 格舰船、10–30 人长期服务器是测试目标，目前没有容量保证。**

目前已在独立服务器加一个真实客户端上验证百格船（14,709 个方块、24 台箱子／熔炉）的起航、八格航点飞行、抵达制动与悬停。300 格方案已实际生成和装配，但首次飞行测试失败，尚未确认支持。无人观察的物理模拟也有未解决限制。具体数据和失败记录见 `docs/test-report.md`。

## 当前实现

- 矢量驱动核心：船主权限、FE/燃料、遥测、手动控制、航点巡航、悬停与制动。
- 可选 Valkyrien Skies 2.4.11 适配：读取真实船体，在物理 tick 持续施力，控制请求超时自动撤销。缺少依赖时会明确报告不可用。
- 原生驾驶面板：WASD 平移、Space/Shift 升降，H 悬停、X 制动、Esc 退出。尚无地形避障、自动对接和姿态控制。
- 原创 Survey Moon：起伏地形、供氧装备、低重力、前哨、遗迹守卫和一次性探索奖励。当前旅行入口为管理员开发命令，整船跨维度旅行尚未实现。
- 独立船坞维度：分批生成 100/200/300 格多层船体，可加入箱子和工作的熔炉；不覆盖原有方块。
- CC:Tweaked 外设与可选本地 MCP 桥接；外部控制默认关闭，需要船主逐核心授权。

实现和测试结果必须分开看：最新实际验证结果将写入 `docs/test-report.md`。代码存在不表示某个第三方组合、巨舰或长期服务器已验证。

## 开发环境

需要 Java 17。项目的 Gradle wrapper 固定 8.9，Forge 固定 47.4.10。脚本只为当前进程选择 JDK，不更改系统 Java。

```powershell
.\scripts\install-runtime.ps1
.\scripts\dev.ps1 -Task build
.\scripts\dev.ps1 -Task test
.\scripts\dev.ps1 -Task gametest
.\scripts\dev.ps1 -Task client -Ships -Computer
```

JDK 路径不同可增加 `-JavaHome '你的JDK17路径'`。脚本把 Gradle 缓存放在项目 `.gradle-user`；游戏实例分别位于 `run-client`、`run-server`、`run-gametest`，不会使用现有 `.minecraft` 存档。

Linux／macOS 或 CI 可在设置 JDK 17 后运行 `./gradlew test build --no-daemon`。Python 桥接测试使用 `python -B -m unittest discover -s tools -p 'test_mcp_bridge.py' -v`。完整开发流程见 [CONTRIBUTING.md](CONTRIBUTING.md)，变更记录见 [CHANGELOG.md](CHANGELOG.md)。

`-Ships` 从 `runtime/mods` 加载 Valkyrien Skies 和 Kotlin for Forge；这些第三方 JAR 不纳入源码仓库。`-Computer` 加载指定版本 CC:Tweaked。依赖版本、来源和校验信息见 `docs/compatibility.md`。

## 体验入口

在新测试世界开启命令，避免把开发版直接加入重要存档。

1. 获取或合成 `starbridge:ship_core`。手持熔炉燃料右键核心充能；也支持 Forge Energy。
2. 右键自己的核心选择它，再使用 `/starbridge status` 查看实际状态。
3. 自定义装配流程见 `docs/assembly.md`；大型基准船生成见 `docs/benchmark.md`。
4. 装配完成后飞船处于静态停泊状态。供能后使用 `/starbridge launch` 起航，再使用 `/starbridge pilot` 打开驾驶面板。
5. `/starbridge navigate x y z` 指定同维度世界坐标；`hold` 悬停，`stop` 制动，`release` 关闭驱动。
6. 月球开发测试：管理员使用 `/starbridge_expedition enter` 到达 X/Z=0/0 的前哨，`/starbridge_expedition status` 查询遗迹坐标；遗迹在 X/Z=128/0，**右键中央磁石**开始战斗及领取奖励。管理员使用 `/starbridge_expedition return` 返回记录的位置。请先合成氧气面罩并准备武器、食物；前哨没有免费装备。详细步骤和返程限制见 `docs/content.md`。

驱动耗尽能源不会产生免费推力；制动也需要能量。航点导航并不感知地形，必须提供无遮挡路线。

## 电脑与 AI

CC:Tweaked 电脑连接核心后，船主先运行 `/starbridge computer pair <电脑ID>`。外设类型为 `starbridge_ship`，提供 `getStatus()`、`navigate(x,y,z)`、`hold()`、`stop()`。

```lua
local ship = peripheral.find("starbridge_ship")
assert(ship, "未找到飞船核心")
print(textutils.serialize(ship.getStatus()))
ship.hold()
```

MCP 是独立可选工具，不需要特定模型供应商。管理员启动 `/starbridge agent start`，船主再对所选核心执行 `/starbridge agent allow true`。凭证只写当前实例的 `config/starbridge-token.txt`，不提交 Git。详见 `docs/mcp.md`。

## 许可证与内容边界

Starbridge 的原创代码与原创生成纹理采用 GPL-3.0-only，见 `LICENSE`。依赖分别保留其上游许可证。本项目引用 Minecraft 自带物品图标，但不重新分发其原始美术。

本原型没有复制 ExtraPlanets / GalaxySpace 的代码或素材。未来按单项授权决定哪些经典内容可移植，哪些需要原创实现；免费提供、源码可见不等于允许重新发布修改版。
