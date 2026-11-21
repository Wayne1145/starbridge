# 参与开发

主分支为 `main`。功能、修复和实验从 `main` 创建短期分支，例如 `codex/fix-ship-loading`，通过 Pull Request 合并。提交应围绕一个可审查的改动，说明问题、行为变化和验证结果；不要把未通过的原生物理测试写成已支持功能。

## 构建与验证

需要 JDK 17，MCP 工具测试需要 Python 3.11 或更高版本。使用仓库内固定版本的 Gradle wrapper。

```sh
./gradlew test build --no-daemon
python -B -m unittest discover -s tools -p 'test_mcp_bridge.py' -v
git diff --check
```

Windows 可使用 `scripts/dev.ps1 -Task build -JavaHome <JDK17目录>`。脚本优先选择传入路径、`JAVA_HOME` 或 `JAVA_HOME_17_X64` 中的 JDK 17，也识别本机常用安装路径；它不会修改系统 Java 设置。

基本构建不需要下载运行时的 VS／Kotlin 模组。要做带飞船的本地验证，先运行 `scripts/install-runtime.ps1`，再使用 `scripts/dev.ps1 -Task client -Ships -Computer`。联网依赖首次下载完成前不要使用 `-Offline`。

CI 执行 Java 单元测试、构建和 Python 桥接测试，并保留报告和开发 JAR。CI **不验证**原生物理、客户端界面、巨舰性能或多人容量。真实游戏测试的命令和边界见 [测试报告](docs/test-report.md)、[兼容性记录](docs/compatibility.md)及[基准说明](docs/benchmark.md)。

## 复现两名船员往返 UnBound 月球

用 JDK 17，在同一台机器和同一网络命名空间运行一台独立服务器与两个有图形渲染能力的客户端。为避免三个长时间运行的 Gradle 任务争用同一项目目录，原仓库运行服务端，另外两个工作树分别运行船长和工程师。三个工作树使用同一提交。以下命令的 `./gradlew` 在 Windows PowerShell 中写作 `.\gradlew.bat`。

```sh
git worktree add --detach ../starbridge-voyage-captain HEAD
git worktree add --detach ../starbridge-voyage-engineer HEAD
```

在原仓库运行 `pwsh -File scripts/install-runtime.ps1 -Unbound`，把校验过的 `runtime/mods/` 复制到两个新工作树的同一相对位置。也可以按脚本内的地址、文件名和 SHA-512 手动准备四个 JAR。CC:Tweaked 由 Gradle 下载。在每个工作树先运行 `./gradlew classes prepareRuns -PwithShips -PwithComputer -PwithUnbound`，以免首次编译和下载占用测试计时。

**准备三个新实例目录。** 在原仓库创建 `run-voyage-server/`，在船长、工程师工作树分别创建 `run-voyage-captain/`、`run-voyage-engineer/`。每个实例根目录都放一个名为 `starbridge-disposable-voyage.fixture` 的空文件。为每次重跑选择新目录，命令中的 `serverDir` 或 `clientDir` 随之调整。

- 在服务端实例中，阅读并接受 Minecraft EULA 后，自己将 `eula=true` 写入该实例的 `eula.txt`。在旁边创建如下 `server.properties`。仅监听同机回环地址，两名开发测试身份各用独立玩家数据，无需服主管理权限。
- 为两个客户端各准备一份 `options.txt`，填入下方设置以直接进入标题界面。在各自的 `config/forge-client.toml` 写入 `[client]`，下一行写 `showLoadWarnings = false`，让首次启动的警告弹窗不阻断自动连接。这些设置只用于临时实例。

```properties
# run-voyage-server/server.properties
server-ip=127.0.0.1
server-port=25583
online-mode=false
enforce-secure-profile=false
max-players=2
allow-flight=true
spawn-protection=0
difficulty=peaceful
level-seed=764123
view-distance=4
simulation-distance=4
max-tick-time=150000
```

```text
version:3465
onboardAccessibility:false
skipMultiplayerWarning:true
pauseOnLostFocus:false
fullscreen:false
renderDistance:3
simulationDistance:5
maxFps:15
```

**依次启动。** 在三处工作树的独立终端分别运行对应命令。服务端日志出现 `Done (` 后启动船长，看到 `OrbitCaptain joined the game` 再启动工程师。`-PvoyageCrew` 等待标题界面就绪后连到 `127.0.0.1:25583`，普通客户端的 `-Pconnect` 选项与它互斥。服务端和两个客户端可以运行在隔离的图形会话或有 OpenGL 支持的虚拟机内。Linux 若已配置可用的虚拟显示，可在两条客户端命令前各加 `xvfb-run -a`。客户端不需要鼠标、键盘或桌面前台焦点。

```sh
# 原仓库
./gradlew runServer --console=plain -PserverDir=run-voyage-server -PvoyageSmoke -PwithShips -PwithComputer -PwithUnbound

# ../starbridge-voyage-captain
./gradlew runClient --console=plain -PclientDir=run-voyage-captain -PclientName=OrbitCaptain -PvoyageCrew -PwithShips -PwithComputer -PwithUnbound

# ../starbridge-voyage-engineer
./gradlew runClient --console=plain -PclientDir=run-voyage-engineer -PclientName=OrbitEngineer -PvoyageCrew -PwithShips -PwithComputer -PwithUnbound
```

**核对结果。** 服务端 `logs/latest.log` 须出现两个不同身份的加入记录，两个客户端各自的日志须有连接与月球停泊消息。服务端写出 `run-voyage-server/voyage-result.json` 后自行停服，结束两个测试客户端即可。检查 `pass` 为 `true`、`outboundCrew` 与 `returnCrew` 都为 `2`，同时核对每程扣费、货物数和运行警告。对照数据和场景边界见[远征实测记录](docs/expedition-verification.json)与[测试报告](docs/test-report.md)。

记录中的 `a5` 在隔离的图形会话中运行，两名玩家各自通过游戏协议连接独立服务器。上述 Gradle 入口运行相同的客户端和服务端用例。服务器预设观测条件、装配停泊测试船、把两名船员放上甲板，并在月球靠泊后向箱子放货。验证单人生存时另开普通生存新档，省略 `-PvoyageSmoke`、`-PvoyageCrew` 和标记文件，亲自观测、造船、登船、访月、采样并返港。验证记录应来自实际操作，无需管理员设置坐标或解锁进度。[装配自己的船](docs/assembly.md) · [第一次远征](docs/voyage.md)

## 仓库边界

- 提交源码、资源、构建脚本、测试和经过脱敏的测量结果。
- `build/`、`.gradle-user/`、`run-*/`、`runtime/`、`.research/` 和 `.tools/` 是本地环境，不提交。
- 不提交存档、玩家身份、真实 token、私钥、个人绝对路径或机器专用代理配置。
- Gradle wrapper JAR、原创 PNG 和 GameTest NBT 是必要源码资源，应提交。
- 第三方模组从官方渠道获取并保留原许可；不要将 Galacticraft 附属包代码或素材视为默认可再发布。

## 版本与交付

当前为 `0.2.0-dev` 开发原型。通过验证的变更记录在 [CHANGELOG.md](CHANGELOG.md)；只有明确确定的发行版本才创建版本标签或 GitHub Release。CI 下载产物不等于稳定发行版。
