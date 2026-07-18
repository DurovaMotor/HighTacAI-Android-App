# Windows 本机 MQTT Broker 部署说明

创建：2026-07-08

现场网络更新：2026-07-15

本说明用于当前 Windows 开发电脑给 HighTac 声光寻物平台提供局域网 MQTT broker。Eclipse Mosquitto 只负责后台与 eStation 基站之间的消息传输；Android App 不再直连 MQTT，而是通过 HighTac Platform API 共享绑定、命令和状态。

## 当前部署状态

当前电脑已通过 `winget` 安装 Eclipse Mosquitto 2.1.2。现场网络固定为：

实际运行情况：

| 项目 | 当前值 |
| --- | --- |
| 现场 Wi-Fi | `Durova-5G` |
| 主路由器 | 中兴 `ZXHN G7615V2` |
| LAN 网关 / 网段 | `192.168.1.1/24` |
| 开发电脑 Wi-Fi MAC | `84:9E:56:07:18:B1` |
| 开发电脑静态 IPv4 | `192.168.1.105/24` |
| DNS | `223.5.5.5`、`8.8.8.8` |
| DHCP 地址池 | `192.168.1.2` - `192.168.1.104`，不包含静态地址 `.105` |
| Windows 网络类别 | `Private`（仅适用于可信的 `Durova-5G`） |
| HighTac Web/API | `http://192.168.1.105:8088` |
| 项目 broker | `mqtt://192.168.1.105:1884` |
| 用户名 | `hightac_mqtt` |
| 密码 | 使用本机受保护配置中的现场值，不写入文档或 Git |
| TLS | 关闭 |
| 认证 | 用户名 + 密码 |
| ACL | 允许 `hightac_mqtt` 读写 `/estation/#` |
| 运行配置 | `tools/mqtt/runtime/config/mosquitto.conf` |

说明：Mosquitto 安装器自动启动的 Windows 服务占用 `1883`，但只监听 `127.0.0.1/::1`，不能供平台和基站使用。HighTac 项目 broker 使用 `1884`，启动后监听 `0.0.0.0:1884`。2026-07-16 的实机检查中，项目 broker、Platform API 和真实基站 `90A9F7301427` 均在线，基站约每 20 秒发布一次心跳。

静态地址注意事项：`192.168.1.105/24` 只适用于当前 `Durova-5G` / `192.168.1.0/24` 网络。电脑切换到其他 Wi-Fi 前，应先把 WLAN IPv4 恢复为 DHCP；如果仍需固定地址，必须按新网络的网关和地址池重新配置，不能照搬 `.1.105`。

## 启动与停止

在仓库根目录启动当前项目 broker：

```powershell
$env:Path = "C:\Program Files\Mosquitto;$env:Path"
.\tools\mqtt\start-mosquitto.ps1 -Mode Native -Port 1884 -Username hightac_mqtt
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

启动 broker 后执行以下 smoke test：

```powershell
$env:Path = "C:\Program Files\Mosquitto;$env:Path"
.\tools\mqtt\test-mqtt-smoke.ps1 -HostName 127.0.0.1 -Port 1884 -Username hightac_mqtt
.\tools\mqtt\test-mqtt-smoke.ps1 -HostName 192.168.1.105 -Port 1884 -Username hightac_mqtt
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
| 连接方式 | 通过网线连接到 `Durova-5G` 所在的中兴 `ZXHN G7615V2` 路由器 |

在 eStation 基站内置管理页中填写：

| 参数 | 值 |
| --- | --- |
| MQTT server / host | `192.168.1.105` |
| MQTT port | `1884` |
| TLS / SSL | 关闭 |
| Username | `hightac_mqtt` |
| Password | 使用受保护配置中的现场值；不要写入文档、截图或 Git |
| Client ID | 如页面可填，建议使用基站 SN |
| Keep alive | 20 到 60 秒 |
| Clean session | 开启 |
| QoS | MVP 先用 0 |

如果页面要求“服务器地址:端口”格式，填写：

```text
192.168.1.105:1884
```

基站内置管理页资料：

| 项目 | 值 |
| --- | --- |
| 默认管理地址 | `http://192.168.172.173:8083/` |
| PC 临时配置网段示例 | `192.168.172.172` |
| 默认用户名 | `admin` |
| 默认密码规则 | `kwp` + MAC 地址后四位，例如 `kwp02CD` |

保存 MQTT 参数后重启基站。Platform 后台或 MQTTX 订阅真实基站 SN 的 `/estation/{ID}/#`，应能在约 30 秒内看到 heartbeat。

当前策略：真实 Android 手机不再使用 USB 反向代理，也不再连接 broker。手机连接 `Durova-5G` 后访问 `http://192.168.1.105:8088`；只有 HighTac Platform 后台和基站连接 `192.168.1.105:1884`。不能把手机服务器地址填写为 `127.0.0.1`、`localhost` 或 Android 模拟器专用的 `10.0.2.2`。

## Android App 参数

App 声光寻物页只配置平台服务器：

| 字段 | 值 |
| --- | --- |
| 服务器地址 | `http://192.168.1.105:8088` |
| 设备身份 | App 自动生成哈希身份和安装密钥，不使用 Wi-Fi MAC |
| MQTT 用户名/密码 | App 不保存、不显示 |

新手机第一次连接后会显示“等待管理员批准”。管理员使用网页中的 Android 设备页面批准并命名手机，App 随后取得一次性 device token 并保存到 Android Keystore。未批准、已撤销或后台不可用时，App 可以展示最后缓存，但禁止绑定、解绑、亮灯和灭灯。

手机、电脑和基站必须处在 `Durova-5G` 对应的同一 LAN。2026-07-16 使用 USB 连接的 OnePlus `PLF110`（Android 16，Wi-Fi 地址 `192.168.1.61`）已验证可以从手机 TCP 连接电脑的 `8088` 和 `1884` 端口；最终 App 业务仅使用 `8088`。

## 防火墙

若真实手机无法连接 API，或基站无法连接 broker，先确认 `8088`/`1884` 正在监听，再检查 Windows 网络类别和防火墙。

当前 `Durova-5G` 已标记为 `Private`，项目使用固定名称、仅 `Private` + `LocalSubnet` 的入站规则。用管理员 PowerShell 重复应用：

```powershell
Set-NetConnectionProfile -InterfaceAlias 'WLAN 2' -NetworkCategory Private
.\installer\windows\Set-HighTacFirewall.ps1 -Action Ensure -WebPort 8088 -MqttPort 1884 -Force
```

电脑切换到其他 Wi-Fi 前，应恢复 DHCP或按新网络重新规划静态地址，并重新检查该网络是否可信。不要把 `Durova-5G` 的 Private 分类、`.105` 静态地址或防火墙假设机械复制到公共网络。

不要在 Public Profile 或公网暴露明文 MQTT。后续进入生产网络时再升级 TLS、VPN 或专用内网隔离。

## Topic 清单

| 方向 | Topic |
| --- | --- |
| Platform 后台订阅基站心跳 | `/estation/{ID}/heartbeat` |
| Platform 后台订阅基站回执 | `/estation/{ID}/result` |
| Platform 后台发布点亮/灭灯任务 | `/estation/{ID}/task` |
| Platform 后台发布分组任务 | `/estation/{ID}/group` |
| Platform 后台发布绑定任务 | `/estation/{ID}/bind` |

## 排障速查

| 现象 | 优先检查 |
| --- | --- |
| App 显示后台不可用 | 确认 `http://192.168.1.105:8088/api/v1/health/ready`、TCP 8088、防火墙和同网段 |
| App 等待批准 | 在网页 Android 设备页面批准；不要重新安装或反复登记覆盖状态 |
| 后台可用但基站离线 | 确认 `192.168.1.105:1884`、基站账号密码、心跳 Topic 和基站重启 |
| 本机可连，手机/基站不可连 | Windows 防火墙、Private Profile、是否同网段、路由器 AP isolation |
| 基站无 heartbeat | 基站 MQTT host/port/user/password、保存后是否重启、SN 是否填错 |
| 后台已连 broker 但无基站 | 后台订阅的 `{ID}` 必须与真实基站 SN 完全一致，Topic 区分大小写 |
| 端口冲突 | `1883` 被安装器默认服务占用，项目 broker 使用 `1884` |
| 切换 Wi-Fi 后断网 | 将 WLAN IPv4 恢复 DHCP，或按新网络网关和 DHCP 地址池重新规划静态 IP |
| 开 VPN 后本机 curl 超时 | 仅诊断命令增加 `--noproxy '*'`，不要关闭 Codex 所需 VPN |

## 参考

- Eclipse Mosquitto config manual: https://mosquitto.org/man/mosquitto-conf-5.html
- Eclipse `mosquitto_passwd` manual: https://mosquitto.org/man/mosquitto_passwd-1.html
- Eclipse Mosquitto Docker image: https://hub.docker.com/_/eclipse-mosquitto
- Microsoft `New-NetFirewallRule`: https://learn.microsoft.com/powershell/module/netsecurity/new-netfirewallrule
