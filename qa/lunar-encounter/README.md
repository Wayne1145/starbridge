# 复测首次航行界面和原月球首领

在 Forge 47.4.28 / Minecraft 1.20.1 的一对独立专服和图形客户端中复测。两份实例同属一个测试目录，分别命名为 `server` 和 `LunarTester`，均使用 JDK 17。仅在一次性测试存档中使用此场景工具。

1. 使用正式包按[原月球生成步骤](../retrofit-lunar-seed/README.md)产生刷怪器与宝箱，确认刷怪器尚未触发、宝箱处于上锁状态。将那份存档复制进独立的 `server/world`。
2. 在仓库根运行 `./gradlew.bat -I qa/lunar-encounter/fixture.init.gradle reobfLunarEncounterQaJar`。将 `build/qa/lunar-encounter` 生成的测试 JAR、本次 0.3.0-dev 的 Starbridge 与 Retrofit 放入两端。两端的 `retrofit/mods` 均使用运行记录中的 Galacticraft、Advanced Rocketry 和 LibVulpes 原包。
3. 在两端实例根目录各放一份空的 `starbridge-lunar-encounter.fixture`，并把测试服的本机地址与端口写入客户端 `lunar-server.txt`，例如 `127.0.0.1:25641`。服务端设 `server-ip=127.0.0.1`、`gamemode=survival`、`difficulty=normal`、`spawn-protection=0`，测试玩家没有管理员权限。启动服务器，再启动客户端进入标题画面。测试机的专服和客户端共享这个目录树，是为了记录点击结果。
4. 客户端正常连接后使用测试服发放的未绑定终端，点选船坞页面，截图并关闭。服务端发放装备、药效，并将玩家放入首领房。客户端靠近并挥剑攻击自然生成的首领，朝掉落的钥匙移动。服务器确认玩家拾取后将角色放到原宝箱旁，客户端使用钥匙打开原容器，移走一项战利品。
5. 阅读 `LunarTester/lunar-ui-actions.log`、两份首航截图与 `server/encounter-result.json`。复核 `unpairedSlateGuideCompleted`、`observedBoss`、`observedDroppedKey`、`keyInInventory`、原宝箱 NBT 中的 `isLocked`、`menu` 和实际库存。本次副本的首领奖励中有两颗铁锭。临时实例使用完毕后正常退出客户端和专服。

[原始结果、截图与哈希](../../docs/original-runtime-verification.json)

在两个扩展同装的专服和客户端，另将 `starbridge-encounter-site.txt` 填为 `ceres` 或 `venus`。测试选择同种子的谷神星或金星原地牢，用例使用对应的上游钥匙和箱子坐标。[正式包三份实测](../../docs/original-worlds-production.json)
