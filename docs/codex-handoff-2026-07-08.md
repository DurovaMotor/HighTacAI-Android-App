# Codex 迁移交接记录 - 声光寻物大版本

> 状态：V1 历史交接归档。本文中的 Android 直连 MQTT、App Broker 配置等内容已被 HighTac Platform V2 取代，仅用于追溯。当前部署和迁移必须以 `hightac-web-platform-architecture-plan.md`、`hightac-platform-operations.md` 及 `mqtt-local-broker-windows.md` 为准。

创建：2026-07-08

现场网络更新：2026-07-15

本文件用于把当前 Codex 对话中的关键上下文沉淀到 Git 仓库，方便在另一台笔记本克隆仓库后继续开发。原始聊天记录不作为代码资产迁移；后续 Codex 可直接阅读本文件、需求文档和代码继续承接。

## 仓库信息

| 项目 | 值 |
| --- | --- |
| GitHub 仓库 | `https://github.com/DurovaMotor/HighTacAI-Android-App.git` |
| 当前工作分支 | `codex/light-finding-requirements` |
| Android 项目路径 | `C:\0.Files\Program\HighTacAI-Android-App` |
| Java 版本 | Temurin OpenJDK 21.0.11 LTS |
| `JAVA_HOME` | `C:\Users\ooo\.jdks\jdk-21.0.11+10` |

新电脑克隆后按实际用户名安装或配置 JDK 21，并参考 `AGENTS.md`。

## 用户需求摘要

- 使用 GitHub 做迭代版本管理。
- App 新增大版本：软硬件协同，连接档口声光灯条寻物系统。
- 第一版不用 Kaicom 平台登录。
- MQTT broker 初期部署在开发笔记本本机。
- 现场设备是普通 Android 手机。
- 灯条类型只支持夹子灯条。
- 产品编码和灯条 ID 初始数据由 App 手动扫码绑定。
- 查价模块的配件卡片需要支持：
  - 未绑定时显示 `扫码绑定灯条`。
  - 已绑定时显示 `亮灯` / `灭灯`。
- 已有实物基站和灯条用于开发验收。

## 硬件信息

| 项目 | 值 |
| --- | --- |
| 基站型号 | `ETAP05-D1` |
| 电源输入 | `DC12V` |
| 批次号 | `Y20251026000228` |
| SN | `90A9F7301427` |
| 连接方式 | 基站通过网线连接到 `Durova-5G` 所在的中兴 `ZXHN G7615V2` 路由器 |

## 二开文档结论

在线文档：https://platform.kaicom.cn:9006/platform/file-server/eStationMqtt.html

关键点：

- eStation 基站是 MQTT client。
- App 不是直接连接基站，而是 App 和基站都连接同一个 MQTT broker。
- 所有 topic 遵循 `/estation/{ID}/...`，`{ID}` 是基站 SN。
- App 订阅：
  - `/estation/{ID}/heartbeat`
  - `/estation/{ID}/result`
- App 发布：
  - `/estation/{ID}/task`
  - 后续可扩展 `/bind`、`/group`、`/ota`
- QoS 使用 `0`。
- 当前 MVP 不启用 TLS，基站配置页中 TLS/SSL 需要关闭。

## 已实现内容

新增灯条寻物协议、领域模型、数据存储、MQTT 客户端和 UI：

- `app/src/main/java/com/example/deepchatdemo/light/**`
- `app/src/main/java/com/example/deepchatdemo/ui/light/**`
- `app/src/test/java/com/example/deepchatdemo/light/**`

集成点：

- `app/src/main/java/com/example/deepchatdemo/ui/DeepChatScreen.kt`
  - 顶部模式新增 `寻物`。
- `app/src/main/java/com/example/deepchatdemo/ui/price/PriceLookupScreen.kt`
- `app/src/main/java/com/example/deepchatdemo/ui/price/PriceResultCard.kt`
  - 价格查询配件卡片接入灯条绑定、亮灯、灭灯入口。

本机 MQTT broker 脚本：

- `tools/mqtt/start-mosquitto.ps1`
- `tools/mqtt/stop-mosquitto.ps1`
- `tools/mqtt/status-mqtt.ps1`
- `tools/mqtt/test-mqtt-smoke.ps1`
- `docs/mqtt-local-broker-windows.md`

## 当前验证结果

已经验证：

- `:app:testDebugUnitTest` 通过。
- `:app:installDebug` 曾成功安装到 Android 手机。
- 当前策略改为真实手机走 Wi-Fi / 局域网直连本机 Mosquitto；App Broker 地址必须填写电脑局域网 IP，不能填写 USB reverse 专用的 `127.0.0.1:1884`。
- App 能订阅真实基站 SN 的：
  - `/estation/90A9F7301427/heartbeat`
  - `/estation/90A9F7301427/result`
- 本机发布模拟 heartbeat 后，App 可显示 `MQTT 就绪 / 基站在线`。

当前待验证：

- 2026-07-15 检查时项目 `1884` broker 尚未运行；系统 Mosquitto 只监听 localhost `1883`。
- 启动项目 broker 后，将 App 和基站统一配置为 `192.168.1.105:1884`，再验证真实 heartbeat 和回执。

## 现场 MQTT 配置

本机 broker 使用 Eclipse Mosquitto：

| 参数 | 值 |
| --- | --- |
| 现场 Wi-Fi | `Durova-5G` |
| 路由器 / 网关 | 中兴 `ZXHN G7615V2` / `192.168.1.1` |
| 电脑 Wi-Fi MAC | `84:9E:56:07:18:B1` |
| 电脑静态 IPv4 | `192.168.1.105/24` |
| DNS | `223.5.5.5`、`8.8.8.8` |
| 路由器 DHCP 池 | `192.168.1.2` - `192.168.1.104` |
| Windows 网络类别 | 当前 `Public`；现有 1884 防火墙规则 Profile 为 `Any` |
| Broker 地址 | `192.168.1.105` |
| 端口 | `1884` |
| 监听 | `0.0.0.0:1884` |
| 用户名 | `hightac_mqtt` |
| 密码 | 使用本机受保护配置中的现场值，不写入文档或 Git |
| TLS | 关闭 |
| ACL | `hightac_mqtt` 可读写 `/estation/#` |

注意：

- `1883` 被 Mosquitto Windows 服务占用，且只监听 localhost，不给真实基站使用。
- 当前电脑需要启动项目 broker，基站和 App 都填写 `192.168.1.105:1884`。
- `192.168.1.105` 是 `Durova-5G` 的现场静态地址。切换其他 Wi-Fi 前应恢复 DHCP，或按新网段重新配置静态地址。
- 建议确认现场网络可信后将 `Durova-5G` 改为 Windows `Private`，并把 1884 入站规则从 `Any` 收窄到 `Private`。
- 不要把 `tools/mqtt/runtime/config/passwordfile`、日志、数据库提交到 Git。

启动 broker：

```powershell
$env:Path = "C:\Program Files\Mosquitto;$env:Path"
.\tools\mqtt\start-mosquitto.ps1 -Mode Native -Port 1884 -Username hightac_mqtt
```

查看本机局域网 IP：

```powershell
Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object { $_.IPAddress -like '192.168.*' } |
  Select-Object InterfaceAlias,IPAddress,PrefixLength
```

监听真实基站：

```powershell
$env:Path = "C:\Program Files\Mosquitto;$env:Path"
mosquitto_sub -h 127.0.0.1 -p 1884 -u hightac_mqtt -P '<site-mqtt-password>' -t '/estation/90A9F7301427/#' -v
```

## 真实基站未连入的当前判断

最可能原因不是 App 协议错误，而是基站 MQTT 配置或网络可达性问题。

已知现象：

- 二开文档要求基站作为 MQTT client 主动连接 broker。
- App 已经能连接 broker 并订阅正确 topic。
- 手机在同网段时可以 TCP 打到电脑 `1884`。
- 真实基站没有出现在 Mosquitto 连接日志中。

现场排查优先级：

1. 进入基站管理页。
2. 将 MQTT server/host 改为 `192.168.1.105`。
3. 端口填 `1884`。
4. 用户名 `hightac_mqtt`。
5. 密码填写受保护配置中的现场值，不要粘贴到文档或聊天记录。
6. TLS/SSL 关闭。
7. 保存后重启基站。
8. 观察 `/estation/90A9F7301427/#` 是否出现 heartbeat。
9. 如果没有，去路由器 DHCP 客户端列表找基站 IP/MAC，确认基站和电脑在同一网段。

## 新电脑接手步骤

1. 安装 Git、Android Studio、Android SDK、Temurin JDK 21、Mosquitto。
2. 克隆仓库：

```powershell
git clone https://github.com/DurovaMotor/HighTacAI-Android-App.git
cd HighTacAI-Android-App
git checkout codex/light-finding-requirements
```

3. 配置 Java：

```powershell
$env:JAVA_HOME = 'C:\Users\ooo\.jdks\jdk-21.0.11+10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

如果新电脑用户名或 JDK 路径不同，替换为新电脑实际路径。

4. 验证测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

5. 启动本机 MQTT：

```powershell
$env:Path = "C:\Program Files\Mosquitto;$env:Path"
.\tools\mqtt\start-mosquitto.ps1 -Mode Native -Port 1884 -Username hightac_mqtt
```

6. 在 App 和基站管理页填新电脑当前局域网 IP，不要沿用旧电脑 IP。

## 另一台电脑给 Codex 的承接提示词

```text
你现在接手 HighTacAI-Android-App 的声光寻物大版本迭代。

请先阅读并遵守：
- AGENTS.md
- docs/codex-handoff-2026-07-08.md
- docs/mqtt-local-broker-windows.md
- docs/light-finding-major-update-requirements.md

当前目标：
1. 在分支 codex/light-finding-requirements 上继续开发。
2. 先验证 Android 单元测试：.\gradlew.bat :app:testDebugUnitTest
3. 启动新电脑本机 Mosquitto broker，端口 1884，用户名 hightac_mqtt，密码从受保护配置输入，TLS 关闭。
4. 使用新电脑当前局域网 IP 配置 App 和 ETAP05-D1 基站，基站 SN 是 90A9F7301427。
5. 根据 Kaicom 二开文档 https://platform.kaicom.cn:9006/platform/file-server/eStationMqtt.html，确认 App 订阅 /estation/90A9F7301427/heartbeat 和 /estation/90A9F7301427/result，发布 /estation/90A9F7301427/task，QoS 0。
6. 重点解决真实基站没有连入 broker 的问题：进入基站管理页，把 MQTT host 改为新电脑局域网 IP，端口 1884，用户名密码一致，TLS/SSL 关闭，保存后重启基站，并用 mosquitto_sub 监听 /estation/90A9F7301427/#。
7. 不要提交 tools/mqtt/runtime 下的密码文件、日志或数据库；不要回滚 unrelated untracked 文件。

请先汇报你看到的当前 Git 状态、测试结果、broker 状态和下一步硬件联调计划，然后继续执行。
```

## 迁移时需要另行复制的本地资料

以下文件不一定随 Git 仓库迁移，建议用移动硬盘或网盘复制到新电脑：

- `C:\Users\ooo\Desktop\声光寻物系统\基站使用配置说明V1.0(6).pdf`
- `C:\Users\ooo\Desktop\声光寻物系统\萤光寻物APP2.0使用手册-(正式版).pdf`
- `C:\Users\ooo\Desktop\声光寻物系统\a6ee1232-5a4a-48fa-a8ab-2235368e1dc2 (1).apk`
- `C:\Users\ooo\Desktop\声光寻物系统\reverse_output`

这些资料用于协议核对、UI 参考、逆向确认和硬件配置，不建议直接提交普通 Git。
