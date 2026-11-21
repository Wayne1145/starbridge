# 接手 Starbridge 开发

1. 在仓库根运行 `git status --short --branch` 和 `git log -3 --oneline --decorate`，核对当前分支、最近提交和工作区里的改动。结合用户最近的指示，在交接记录中找到本轮目标与尚未完成的步骤。[项目交接](.serena/memories/start-here.md)
2. 以[融合大纲](docs/integration-plan.md)为当前玩法目标。普通生存玩家要能用图形界面、方块交互和可改绑按键完成整趟任务，关闭作弊实测。按[代码索引](.serena/memories/code-map.md)找到实现和测试，遇到改名或移动的类用 `rg` 查当前仓库。[旧远征路线](docs/unbound-roadmap.md)记录 0.2 原型。
3. 回答“是否实测通过”时，先核对该版本的原始运行结果、日志和测试断言，再核对报告。记录用的是单人、模拟玩家还是真实客户端，以及实际操作、跳过的检查和已知失败。若记忆和报告有出入，以可复核的运行证据为准。复现命令见开发指南。[验证索引](.serena/memories/verification.md) · [远征实测数据](docs/expedition-verification.json) · [测试报告](docs/test-report.md) · [开发指南](CONTRIBUTING.md)

目标或下一步改变时，更新项目交接。入口迁移时，更新代码索引。新测试改变结论时，在报告和相应数据文件中记录版本、场景、结果与原始证据路径，再更新验证索引。记忆中保留结论、待办和链接，详细测量值放在测试记录里。

Windows 下从仓库根用 `./scripts/serena.ps1 project index .` 索引 Java，用 `./scripts/serena.ps1 memories read start-here .` 回读项目记忆。该入口只在索引子进程中关闭 Eclipse 的全机 JDK 探测，适合装有多个 Java 版本的电脑。游戏和构建继续使用 JDK 17。
