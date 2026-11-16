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

## 仓库边界

- 提交源码、资源、构建脚本、测试和经过脱敏的测量结果。
- `build/`、`.gradle-user/`、`run-*/`、`runtime/`、`.research/` 和 `.tools/` 是本地环境，不提交。
- 不提交存档、玩家身份、真实 token、私钥、个人绝对路径或机器专用代理配置。
- Gradle wrapper JAR、原创 PNG 和 GameTest NBT 是必要源码资源，应提交。
- 第三方模组从官方渠道获取并保留原许可；不要将 Galacticraft 附属包代码或素材视为默认可再发布。

## 版本与交付

当前为 `0.1.0-dev` 开发原型。通过验证的变更记录在 [CHANGELOG.md](CHANGELOG.md)；只有明确确定的发行版本才创建版本标签或 GitHub Release。CI 下载产物不等于稳定发行版。
