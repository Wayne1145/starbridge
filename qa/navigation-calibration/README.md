# 原图纸校准舰桥

在星图点需要更高等级的行星，再点「航线校准」。列表中的图纸、火箭和随身物品来自当前存档。点一件尚未取得的物品可查看取得线索。船主到舰桥旁或自己的船上，可以直接选择背包或副手里的原物品。校准保留物品数量、名字和标签。两端使用相同的开发包，导航网络协议为 9。旧舰桥继续读取原有的 `NavigationTier`。

## 复核已保存的结果

从仓库根执行下面的命令，可用 Python 标准库读取归档中的原区块和试验记录。

```sh
python -B qa/navigation-calibration/verify.py qa/navigation-calibration/evidence/dedicated-calibration.zip
```

这轮使用全扩展 Forge 专服和两名不同身份的非 OP 模拟玩家。夹具准备舰桥、绑定原火箭身份，并通过原物品注册表给玩家测试所需的图纸和火箭。两次正常关服后，存档中两座舰桥保留各自的 UUID、船主和十级、四级科技。另在同一上游组合测试了旧存档缺省等级、已有七级、错误变种、换槽、过远、他人的船和物品完整性。[专服记录](../../docs/navigation-calibration-verification.json)

## 在独立存档复测

在含锁定原模组且已接受游戏协议的 Forge 专服模板上，用 JDK 17 构建主包和这个专用测试包。把两份 JAR、模板目录及 Java 17 可执行文件传给 `run_server.py`。第一个参数应是新的试验目录。脚本从已安装文件核对 SHA-256，使用回环地址和 `forgeserver nogui` 启动服务端，再重开同一存档读取结果。

```sh
./gradlew -I qa/navigation-calibration/fixture.init.gradle verifyProductionJar reobfNavigationCalibrationQaJar
python -B qa/navigation-calibration/run_server.py NEW_DIRECTORY SERVER_TEMPLATE MAIN_JAR TEST_JAR JAVA17
```

## 独立客户端

GitHub 的 `Remote GUI smoke` 工作流会用锁定的 Galacticraft 和 Retrofit 原包启动独立的中文客户端。玩家在准备了原材料的存档中合成舰桥和终端，由自己的舰桥进入星图，选择火星、查看未携带的火箭、读取已携带的二级图纸，再回到同一星图。夹具把一份原图纸放在背包。工作流收集 `navigation-proof-ui.json` 中的操作结果（归档名 `result.json`）、按键记录和两张界面截图。专服另行覆盖两个扩展和十级图纸。本机保留关闭游戏客户端的设置。[图形验收入口](../../.github/workflows/remote-gui.yml)
