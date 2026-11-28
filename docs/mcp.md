# 用 MCP 控制 Starbridge

`tools/mcp_bridge.py` 是 Python 标准库实现的 MCP stdio 服务，无需安装模型供应商 SDK 或第三方 Python 包。它声明支持 **MCP 2025-11-25**，不声称支持之后的协议版本。支持 Python 3.10 及以上。

桥接器向 MCP 客户端提供七个工具，连接本机的 Starbridge 游戏服务。船舶权限、控制权交接、燃料、推力和飞行状态必须由游戏端再次检查。HTTP 接受导航任务并不表示飞船已经抵达；使用状态工具继续查询。

## 启动

普通船主在出发前右击自己放置的**舰桥控制台**，打开**舰船系统**。点**授权 AI 操控**可允许已连接的代理控制这艘船，点**收回 AI 权限**可随时撤销。每艘船单独授权。驾驶中按手动操纵键或制动可接管飞船，点回巡航可恢复外部控制。

**服主配置 MCP 服务**。游戏服务默认关闭。管理员在本机控制台或有权限的会话中执行 `/starbridge agent start`，监听地址为 `http://127.0.0.1:18765`。普通船主通过前述游戏内按钮管理自己船上的权限。

游戏端 token 位于实例工作目录的 `config/starbridge-token.txt`。本项目专用服务器实例对应 `run-server/config/starbridge-token.txt`，客户端实例对应 `run-client/config/starbridge-token.txt`。桥接器本身不会启用游戏接口、生成权限或启动游戏。

```powershell
py -3 tools/mcp_bridge.py --base-url http://127.0.0.1:18765 --token-file run-server/config/starbridge-token.txt
```

MCP 客户端应以子进程方式启动该脚本并连接 stdin/stdout。正常运行时 stdout 只有逐行 JSON-RPC；错误诊断使用 stderr。它不是面向浏览器的 HTTP MCP 服务。

可配置项：

| 配置 | 作用 |
| --- | --- |
| `--base-url` / `STARBRIDGE_API_URL` | 游戏 HTTP API 的本机地址；默认 `http://127.0.0.1:18765` |
| `STARBRIDGE_TOKEN` | Bearer token；优先级最高 |
| `--token-file` / `STARBRIDGE_TOKEN_FILE` | UTF-8 token 文件；文件首尾换行会去除，支持 UTF-8 BOM |
| `--timeout` | HTTP socket 超时秒数；默认 5，允许大于 0 且不超过 30 |

建议用文件传递 token，不要把 token 写入命令行参数、截图、聊天记录或提交到 Git。MCP 客户端的配置文件也应避免保存明文 token；可以配置 `STARBRIDGE_TOKEN_FILE` 指向已有文件。

客户端配置中的 `command` 应是本机 Python 可执行文件，`args` 应使用脚本和 token 文件的绝对路径。不同客户端的外层配置格式不同，所需进程参数相同：

```json
{
  "command": "C:/Windows/py.exe",
  "args": [
    "-3",
    "C:/projects/starbridge/tools/mcp_bridge.py",
    "--token-file",
    "C:/projects/starbridge/run-server/config/starbridge-token.txt"
  ]
}
```

上例使用 Windows Python launcher 和示例项目路径；请替换为自己的绝对路径。连接单人客户端实例时，将 token 路径中的 `run-server` 改为 `run-client`。token 只在桥接器启动时读取；游戏轮换 token 后应重启桥接器。

## 工具与 HTTP 契约

| MCP 工具 | 参数 | 游戏端路由 |
| --- | --- | --- |
| `list_ships` | `{}` | `GET /v1/ships` |
| `get_star_chart` | `ship_id` | `GET /v1/ships/{UUID}/chart` |
| `set_course` | `ship_id`, `destination` | `POST /v1/ships/{UUID}/voyage/{destination}`，船主需在甲板上，人工驾驶席应空闲 |
| `get_ship_status` | `ship_id` | `GET /v1/ships/{UUID}` |
| `navigate` | `ship_id`, `x`, `y`, `z` | `POST /v1/ships/{UUID}/navigate`，JSON body 为 `{x,y,z}` |
| `hold` | `ship_id` | `POST /v1/ships/{UUID}/hold`，body 为 `{}` |
| `stop` | `ship_id` | `POST /v1/ships/{UUID}/stop`，body 为 `{}` |

所有请求带有 `Authorization: Bearer <token>`。`ship_id` 应使用列表中 `id` 字段的舰船核心 UUID；状态中的 `shipId` 是物理后端编号，不能代替核心 UUID。坐标必须是有限数值，拒绝布尔值、字符串、NaN 与 Infinity。工具不接受额外参数、任意 URL、任意路径或服务器控制台命令。

成功响应同时带 `content` 文本和 `structuredContent`。游戏返回 JSON 对象时直接保留其结构；返回数组等其他 JSON 类型时包装为 `{"data": ...}`。HTTP 错误、参数校验失败和超时返回 `isError: true`，未知工具或损坏的 JSON-RPC 请求返回协议错误。

`navigate` 用于当前维度内的坐标航点。跨星球时先调用 `get_star_chart`，读取 `routes` 中各目的地的 `available`（是否解锁）、`current`（是否在此）、`fare`（单程费用）和 `returnReserve`（返航储备）。例如，从主世界去月球需准备 24,000 FE，其中 12,000 FE 用于返程。

向 `set_course` 传入 `home`、`moon`、`mars`、`belt` 或 `relay`。船主须在线并站在这艘船的甲板上，手动驾驶席须空闲。请求获准后，使用 `get_ship_status` 核对维度，待船员收到到港提示，再下达下一段航令。CC:Tweaked 的对应方法为 `getChart()` 和 `setCourse(destination)`。

## 连接和安全边界

- 仅接受 HTTP/HTTPS loopback 地址。`localhost` 固定转换为 `127.0.0.1`；拒绝公网地址、局域网地址、URL 凭证、路径前缀、查询参数和片段。
- 不使用环境变量配置的 HTTP 代理，不跟随重定向，避免把 Bearer token 发往其他服务。
- 每条输入 JSON-RPC 消息最多 64 KiB。超过限制时返回错误并关闭本次 stdio 会话；游戏 HTTP 响应最多 1 MiB；JSON 嵌套深度最多 64 层。
- 游戏端错误响应的正文不转发，意外回显当前 token 的成功响应也会脱敏。
- 控制命令不会自动重试。超时后游戏可能已经接受任务，应先查询状态再决定是否重新下达指令。
- 本机 token 是游戏服务的访问凭证。MCP 工具提示和工具注解不代替游戏端鉴权；使用专用服务器时，可将桥接器部署在服务器本机，再由受控的客户端连接方式访问 stdio。此脚本不提供远程代理或公开监听端口。
- 本实现同步处理请求，没有后台轮询，也没有实现正在执行的 HTTP 请求取消。手动接管与紧急停止应首先在游戏端可用，不能依赖 AI 的响应时间。

## 接入游戏内的 CC:Tweaked 电脑

在 Forge 1.20.1 双端安装锁定的 CC:Tweaked `1.116.2`，将游戏电脑紧贴舰桥控制台放置。电脑内的 `os.getComputerID()` 可显示设备编号。船主在舰桥的**舰船系统**中填写**电脑编号**，点**配对**。更换设备时可以点**解除**。游戏电脑的配对和 AI 的授权分别设置。

```lua
local ship = assert(peripheral.find("starbridge_ship"), "请将电脑放在舰桥控制台旁")
local moon = ship.getChart().routes.moon
print(moon.provider, moon.dimension, moon.condition)
if moon.available then ship.setCourse("moon") end
```

开航需要船主在线并在船上，原目的地已加载，科技与能源达到星图所列要求。`setCourse` 启动自动巡航。驾驶员手动操纵时，外部控制请求会返回游戏端的拒绝原因。授权、接管和恢复巡航的完整操作见[实机验证](computer-flight-verification.json)。

## 验证

```powershell
py -3 -m unittest discover -s tools -p test_mcp_bridge.py -v
```

桥接器测试使用本机随机端口上的模拟 HTTP 服务，检查七个工具发出的请求、初始化、消息边界、参数校验、鉴权头、重定向拦截、超时处理及敏感信息处理。另一次 Forge 专服实测使用一名非管理员生存角色、一台真实 CC 高级电脑和发行版 MCP stdio 桥。船主在界面配对后，Lua 读取 Galacticraft 原月球星图并启动巡航。MCP 完成制动与恢复巡航。船主发出手动推力后，Lua 和 MCP 都收到拒绝，船主点回巡航后两者恢复控制。该场景使用了预置船舱与能源，物理着陆和返航由另外的用例覆盖。[游戏、Lua 与 MCP 原始记录](computer-flight-verification.json)

协议实现依据：

- [MCP 2025-11-25 生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)：`initialize`、版本协商及 `notifications/initialized`。
- [MCP 2025-11-25 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)：stdio 使用 UTF-8、换行分隔 JSON-RPC，stdout 不包含其他日志。
- [MCP 2025-11-25 工具](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)：`tools/list`、`tools/call`、工具结果及执行错误。
