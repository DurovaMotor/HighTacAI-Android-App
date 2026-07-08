# Windows 本机 MQTT Broker 部署说明

日期：2026-07-08

本说明用于当前 Windows 开发电脑给 HighTac 声光寻物 MVP 提供局域网 MQTT broker。当前选择 Eclipse Mosquitto，原因是安装轻、配置文件简单，适合先跑通 App + eStation 基站 + 夹子灯条闭环。

## 当前部署状态

当前电脑已通过 `winget` 安装 Eclipse Mosquitto 2.1.2。

实际运行情况：

| 项目 | 当前值 |
| --- | --- |
| 局域网 IPv4 | 以当前电脑实时查询为准；2026-07-08 当前旧笔记本为 `192.168.2.105` |
| 项目 broker | `mqtt://<当前电脑局域网IP>:1884` |
| 用户名 | `hightac_mqtt` |
| 密码 | 本机测试默认：`hightac-light` |
| TLS | 关闭 |
| 认证 | 用户名 + 密码 |
| ACL | 允许 `hightac_mqtt` 读写 `/estation/#` |
| 运行配置 | `tools/mqtt/runtime/config/mosquitto.conf` |

说明：Mosquitto 安装器自动启动了 Windows 服务，占用 `1883`，但只监听 `127.0.0.1/::1`。当前 Codex 进程没有停止系统服务的权限，所以项目 broker 暂时使用 `1884` 端口，并监听 `0.0.0.0:1884`，可供同网段手机和基站连接。

## 启动与停止

启动当前项目 broker：

```powershell
$env:Path = "C:\Program Files\Mosquitto;$env:Path"
.\tools\mqtt\start-mosquitto.ps1 -Mode Native -Port 1884 -Username hightac_mqtt -Password 'hightac-light'
```

重置密码并重启：

```powershell
$env:Path = "C:\Program Files\Mosquitto;$env:Path"
.\tools\mqtt\start-mosquitto.ps1 -Mode Native -Port 1884 -Username hightac_mqtt -Password '<new-password>' -ResetPassword
```

停止脚本启动的项目 broker：

```powershell
.\tools\mqtt\stop-mosquitto.ps1 -Mode Native
```

查看状态：

```powershell
$env:Path = "C:\Program Files\Mosquitto;$env:Path"
.\tools\mqtt\status-mqtt.ps1 -Port 1884
```

## 验证结果

以下两条 smoke test 已通过：

```powershell
$env:Path = "C:\Program Files\Mosquitto;$env:Path"
.\tools\mqtt\test-mqtt-smoke.ps1 -HostName 127.0.0.1 -Port 1884 -Username hightac_mqtt -Password 'hightac-light'
.\tools\mqtt\test-mqtt-smoke.ps1 -HostName <当前电脑局域网IP> -Port 1884 -Username hightac_mqtt -Password 'hightac-light'
```

验证 topic：

```text
/estation/90A9F0000000/heartbeat
```

收到的模拟 payload 中包含 `ID=90A9F0000000`，说明本机发布/订阅和账号认证均正常。

## 基站配置参数

当前实物基站：

| 项目 | 值 |
| --- | --- |
| 型号 | `ETAP05-D1` |
| 批次号 | `Y20251026000228` |
| SN | `90A9F7301427` |
| 电源输入 | `DC12V` |
| 连接方式 | 已通过网线连接到 `HCTP-LINK_284E` Wi-Fi 路由器 |

在 eStation 基站内置管理页中填写：

| 参数 | 值 |
| --- | --- |
| MQTT server / host | 当前电脑局域网 IP，例如旧笔记本当前为 `192.168.2.105` |
| MQTT port | `1884` |
| TLS / SSL | 关闭 |
| Username | `hightac_mqtt` |
| Password | `hightac-light`，后续现场可改强密码 |
| Client ID | 如页面可填，建议使用基站 SN |
| Keep alive | 20 到 60 秒 |
| Clean session | 开启 |
| QoS | MVP 先用 0 |

如果页面要求“服务器地址:端口”格式，填写：

```text
<当前电脑局域网IP>:1884
```

基站内置管理页资料：

| 项目 | 值 |
| --- | --- |
| 默认管理地址 | `http://192.168.172.173:8083/` |
| PC 临时配置网段示例 | `192.168.172.172` |
| 默认用户名 | `admin` |
| 默认密码规则 | `kwp` + MAC 地址后四位，例如 `kwp02CD` |

保存 MQTT 参数后重启基站。App 或 MQTTX 订阅真实基站 SN 的 `/estation/{ID}/#`，应能在约 30 秒内看到 heartbeat。

当前已验证：App 使用 USB 反向代理 `127.0.0.1:1884` 连接本机 broker 后，能收到模拟的 `/estation/90A9F7301427/heartbeat` 并显示“基站在线”。真实基站 heartbeat 暂未收到，下一步需要确认基站管理页 MQTT 参数和 Windows 防火墙。

## Android App 参数

App 声光寻物页填写：

| 字段 | 值 |
| --- | --- |
| 基站 SN | 真实基站 SN，例如 `90A9F...` |
| Broker 地址 | 当前电脑局域网 IP，例如旧笔记本当前为 `192.168.2.105` |
| 端口 | `1884` |
| 用户名 | `hightac_mqtt` |
| 密码 | `hightac-light` |

手机必须和当前电脑处在同一 Wi-Fi / 热点 / LAN。Android 模拟器才使用 `10.0.2.2`，真实手机不要填这个地址。

## 防火墙

当前 smoke test 在本机和本机 LAN IP 上都已通过。若真实手机或基站连接失败，优先检查 Windows 防火墙是否允许入站 TCP `1884`。

需要管理员 PowerShell 执行：

```powershell
New-NetFirewallRule -DisplayName "HighTac MQTT Broker TCP 1884" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1884 -Profile Private
```

不要在 Public Profile 或公网暴露明文 MQTT。后续进入生产网络时再升级 TLS、VPN 或专用内网隔离。

## Topic 清单

| 方向 | Topic |
| --- | --- |
| App 订阅基站心跳 | `/estation/{ID}/heartbeat` |
| App 订阅基站回执 | `/estation/{ID}/result` |
| App 发布点亮/灭灯任务 | `/estation/{ID}/task` |
| App 发布分组任务 | `/estation/{ID}/group` |
| App 发布绑定任务 | `/estation/{ID}/bind` |

## 排障速查

| 现象 | 优先检查 |
| --- | --- |
| App 连接失败 | Broker 地址应为当前电脑局域网 IP，端口应为 `1884`，用户名密码一致 |
| 本机可连，手机/基站不可连 | Windows 防火墙、网络 Profile、是否同网段、路由器 AP isolation |
| 基站无 heartbeat | 基站 MQTT host/port/user/password、保存后是否重启、SN 是否填错 |
| App 已连 broker 但无基站 | App 订阅的 `{ID}` 必须与真实基站 SN 完全一致，Topic 区分大小写 |
| 端口冲突 | `1883` 被安装器默认服务占用，项目 broker 使用 `1884` |

## 参考

- Eclipse Mosquitto config manual: https://mosquitto.org/man/mosquitto-conf-5.html
- Eclipse `mosquitto_passwd` manual: https://mosquitto.org/man/mosquitto_passwd-1.html
- Eclipse Mosquitto Docker image: https://hub.docker.com/_/eclipse-mosquitto
- Microsoft `New-NetFirewallRule`: https://learn.microsoft.com/powershell/module/netsecurity/new-netfirewallrule
