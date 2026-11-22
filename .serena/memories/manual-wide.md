# 300 格船的手动飞行验收

自动巡航的三档结果已在 `f2b59e6` 合并到工作分支。手动驾驶单列，见 `qa/manual-wide/README.md`。候选主包 `152eba44`、原机清单 `6c7fa5ac-042c-4bcc-a288-c97210a5b6a2`。驾驶员在真实客户端星图进入第三人称界面，用绑定键和按钮操作。`manual-pilot.jsonl` 与 `manual-server.jsonl` 分别记录接管、舵面、推力、速度、位置和距目标的距离。工程师保持原双层高炉作业。

`wide-300-manual1` 的两人均已满血返港。船长从星图进入第三人称画面后点击「人工接管」。前往原月球和回母港，人工分别前进约 6,051、6,032 导航单位，制动时距目标约 144、163 单位。工程师操作机器时有 17 个服务端采样记录到了人工控制且航速大于 1。逐格复核、物品和两笔收据见 `docs/manual-wide-verification.json`。证据可用 `py -3 qa/manual-wide/inspect_manual.py --replay qa/manual-wide/evidence/wide-300-manual1.zip` 独立重算。首次转向与俯仰在停稳后进行，高速改向和取消着陆后复飞另做一趟。

`wide-300-manualboundary1` 已在最高航速 17 下改变速度方向，船身偏航 27.3 度、俯仰 20.8 度。船长在净空月面泊位改点复飞，退出到距离 293 后人工再次进近，期间 108 个服务端采样均无航费或待提交的航程。已降落原月球，返程在旧测试时钟第 5,101 刻截止。`qa/manual-wide/evidence/boundary-clock-failure.zip` 封存了按先后顺序的原始控制台异常、输入和状态。新的测试时钟在舰桥与访客都准备好之后计时，关闭时保存首个异常。`wide-300-manualboundary2` 已在同一候选主包上完成完整复飞往返。两人满血、原船完整、两笔独立航费均经归档复核。该轮工作窗口最高仅 0.12 单位/刻，同时高速使用机器由 `manual1` 的单独记录覆盖。详见 `docs/manual-wide-verification.json` 的 `boundaryRepeat`。
