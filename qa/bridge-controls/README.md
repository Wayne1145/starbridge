# 验证游戏电脑、MCP 和驾驶员接管

这项测试在独立的 Forge 47.4.28 专服和一个完整的客户端中运行。临时服务器与客户端目录分别叫 `server` 和 `LunarTester`，两者位于同一目录。安装正式 Starbridge、原航天模组、Retrofit 和[版本清单](../../pack/original-spaceflight.lock.json)中的 CC:Tweaked。两端使用 JDK 17。

1. 在仓库根运行 `./gradlew.bat -I qa/bridge-controls/fixture.init.gradle reobfBridgeControlsQaJar`。将 `build/qa/bridge-controls` 中的测试 JAR 放在两端，并在两端实例根目录新建空文件 `starbridge-controls.fixture`。它只用于独立测试存档。
2. 在 `LunarTester/lunar-server.txt` 写入专服的 `127.0.0.1:端口`。服务器使用生存模式、无管理员的测试玩家。为实际 MCP 桥预留本机 `18765`。启动两端及 `python qa/bridge-controls/mcp_session.py <server目录>`。
3. 界面脚本点击舰桥的控制页、填写电脑编号并配对，再启用船主对 MCP 的授权。预置舱内的真实高级电脑从其 CC 文件系统运行 `flight-startup.lua`。程序先验证未配对时的拒绝，然后读取原月球星图并启动自动巡航。
4. 脚本通过发行版的 stdio 桥发起 MCP 工具调用。推动驾驶杆时，电脑和 MCP 中的制动请求均应被拒绝。驾驶员点回巡航后，两种控制都能恢复。

对照 `server/controls-result.json`、`server/mcp-results.json`、`LunarTester/controls-input.log` 和世界内 `computercraft/computer/<编号>` 里的 Lua 输出。实例的 MCP 令牌只保留在测试服自己的配置目录。该次不涉及船坞装配与物理着陆。[实际测试数据](../../docs/computer-flight-verification.json)
