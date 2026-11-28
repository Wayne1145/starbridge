# 复测 Galacticraft 原月球地牢

正式包已经自带相同种子来源。要复测正式包，用 Starbridge 代替下面试验组中的独立附属。早期的独立对照工具针对 Forge 1.20.1 / 47.4.28 与 Retrofit 0.6.2-beta，让旧地形生成阶段使用宿主真实种子。另建测试实例和新世界以重现实验，旧区块会保留其已经写入存档的方块。正式 Starbridge JAR 与这份生成对照工具分别打包。

1. 使用 JDK 17，在仓库根运行 `./gradlew.bat -I qa/retrofit-lunar-seed/fixture.init.gradle reobfLunarSeedQaJar`。输出位于 `build/qa/lunar-seed`。
2. 准备两个干净的本机 Forge 47.4.28 专服，安装同一份 Retrofit `0.6.2-beta` 和 Galacticraft `3.0.12.504`。两份 `server.properties` 均设 `level-seed=576618501`。仅在试验组加入上一步的测试附属。
3. 在两个服务端控制台运行 `execute in retrofit:legacy_dim_-28 run forceload add 402 198 539 335`。等目标区块完成生成并用 `execute in retrofit:legacy_dim_-28 if loaded 470 25 266 run say loaded` 检查，然后 `save-all flush`，移除测试区块的强制加载并 `stop`。该维度键对应原提供器采用默认月球 ID 的测试配置。
4. 对每份存档运行 `python -B tools/upstream_runtime_region.py <世界目录>/dimensions/retrofit/legacy_dim_-28/region <输出JSON>`。试验组中应能找到 `(396,27,193)` 处带首领类与房间边界的 `Dungeon Boss Spawner`，以及 `(369,25,190)` 处锁着的一级 `Treasure Chest`。玩家入场前首领未生成、箱内为空。

[两份存档的观测与原日志校验](../../docs/original-runtime-verification.json) · [玩家测试与原模组发行标识](../../docs/original-runtime.md)
