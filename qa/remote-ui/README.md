# 在 GitHub 上验收中文客户端

[Remote GUI smoke](../../.github/workflows/remote-gui.yml) 在 GitHub 托管的 Ubuntu 24.04 测试机上使用 Xvfb 和软件 OpenGL。每个场景都有自己的显示器会话、游戏目录和存档。`qa/remote-ui` 的客户端入口会检查 GitHub 托管标识、Linux 和本机的 `.tools/no-local-game-clients` 停用标记。开发机上只运行离线用例。

| 场景 | 从玩家入口验证什么 | 测试预置 |
| --- | --- | --- |
| `main-only` | 从标题画面点「模组」，搜索 Starbridge，打开「配置」。选择 Galacticraft，跟随 Retrofit 提示后回到原组件，查看作者链接确认框和可选组件，再返回模组列表。 | 仅装 Starbridge 主包和测试附属，不进入世界。 |
| `slate-only` | 在工作台打开已解锁的两个配方并取出成品，用自制终端打开安装检查，然后从造船图点俯视图上的发射台、侧视图上的塔架，再用 Tab 和 Enter 选择组装机。三次都直接打开高级火箭的文件详情，再跟随运行桥提示，最后点「返回造船图」回到同一终端的原视图。 | 测试附属创建关闭作弊的单人生存世界，提供工作台、原料和起始位置。配方通过原版客户端的配方与库存操作接口选择，安装页使用屏幕坐标点击。 |
| `calibration` | 从舰桥星图选火星，查看缺少的原火箭，再用已携带的原二级图纸校准。回到星图并正常存盘后，核对船主、二级科技及图纸仍在原槽位。 | 在同样的生存开局加装锁定的 Galacticraft 和 Retrofit，背包预置一张原图纸。见[导航校准验收](../navigation-calibration/README.md)。 |
| `pad-floor` | 从原组装机看见缺口，旋转图面，关图使用手中的发射台补格，再开组装机，查看地面并确认可扫描。 | 生存世界预置 3×3 铺面、塔架、朝外的原组装机和一块备用发射台。八个初始铺面里有一格为原着陆台。保存并读取世界区块，核对修好的九格。 |
| `garden` | 等两株甘蔗自然长出第三根，亲手收割和拾取。玩家从箱子取材料，在工作台摆出纸与航图终端的配方，开图查看俯视和侧视，再将终端放回箱中。 | 独立单人生存客户端从七个公开数值重建种植和备料进度。初次加入时背包为空，夹具随后放置两株作物、工作台、箱子及上次备好的材料。生长使用原版随机刻速度 3。[输入和验收范围](../survival-expedition/hosted-garden/README.md) |

PR 更新会自动运行对应工作流。测试从本仓库 `Build and test` 的安装包里取出主 JAR。PR 构建要匹配源码仓库、提交、PR 编号和目标分支提交。可手动运行时，`package_run` 可以指定该版本已经成功的构建编号。`package.py` 检查 Actions 归档摘要、清单和主包散列，客户端从 Forge 实际载入的文件回读主包与测试附属的 SHA-256。Mojang 和 Forge 的版本、安装器、客户端和资源索引锁定在 [runtime.lock.json](runtime.lock.json)。

每个场景使用独立的临时客户端设置，并关闭原版移动教程，方便看清右上角的按钮。`slate-only` 额外保存俯视发射台、侧视塔架、键盘焦点和高级火箭文件页的截图。实际装有高级火箭时点击图格进入原部件配方，需要另行验证。

运行结束后，在该次 Actions 的 `remote-flight-menus-…` 产物里查看各场景的 `actions.txt`、`result.json`、`loaded.json`、`evidence.json` 和 PNG。导航校准和甘蔗试验另附 `saved.json`。失败时结合步骤日志检查最近的输入和游戏报错。证据保留七天。记录由程序向真实渲染客户端输入。判断首次游玩的真人能否自行发现入口，需要单独的试玩记录。

提交 `93fa3df` 的云端运行 `44983280401` 已完成三条缺件路径。俯视发射台和侧视塔架由鼠标点选，组装机在第八次 Tab 后获得焦点，按 Enter 打开安装检查。三次查看高级火箭文件后均回到原来的视图。六张原始截图和操作日志已随仓库保存。[实测范围](../../docs/shipyard-blueprint-input.json) · [操作记录与截图](../../docs/evidence/remote-menus/44983280401-shipyard.zip)

在仓库根运行下列离线回归即可检查旧动作记录、截图、包校验、来源选择、Linux 库筛选和本机启动禁令。Windows 用 `py -3.12` 代替 `python`。

```sh
python -B -m unittest discover -s qa/remote-ui -p 'test_*.py' -v
```
