# HighTac Platform 现场运维手册

更新日期：2026-07-18

适用范围：单站点、1 个 eStation 基站、约 2000 条夹子灯条、10 台 Android 手机的局域网部署。

## 1. 当前现场参数

| 项目 | 当前值 |
| --- | --- |
| Wi-Fi | `Durova-5G` |
| 网关 | `192.168.1.1/24` |
| Windows 服务器 | `192.168.1.105/24`（静态） |
| Web/API | `http://192.168.1.105:8088` |
| MQTT | `192.168.1.105:1884`，TLS 关闭 |
| 基站 | `90A9F7301427`，`ETAP05-D1` |
| Android 服务器地址 | `http://192.168.1.105:8088` |

Android 不连接 MQTT，也不保存 MQTT 密码。HighTac Platform 是唯一 MQTT 客户端和绑定事实源；基站只连接 broker。

## 2. 每日开机检查

正式安装后，`HighTacMqttBroker` 和 `HighTacPlatform` 应随 Windows 自动启动。使用管理员 PowerShell 检查：

```powershell
Get-Service HighTacMqttBroker, HighTacPlatform
.\installer\windows\Test-HighTacHealth.ps1
```

开发模式检查：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 1884,8088
curl.exe --noproxy '*' http://192.168.1.105:8088/api/v1/health/ready
```

就绪响应必须同时表明数据库、迁移和 MQTT bridge 正常。网页仪表盘应分别显示：

- API 可用；
- Broker 已运行且 MQTT bridge 已连接；
- 基站最近心跳在在线阈值内。

Broker 在线不等于基站在线，三种状态不得合并判断。

## 3. 管理员第一次登录

1. 打开 `http://192.168.1.105:8088`。
2. 首次安装用户名为 `Adam`；安装器会生成独立的 32 位随机一次性密码，并只写入受 ACL 保护的操作员配置文件。
3. 使用该一次性密码首次登录后必须立即修改密码，正式密码至少 12 位。
4. 修改成功后删除操作员配置文件，不在聊天、文档、截图、Git 或命令历史中记录任何密码。

浏览器会话使用 HttpOnly Cookie；写操作还需要 CSRF token。不要通过禁用浏览器安全策略绕过认证。

## 4. 新 Android 手机接入

1. 手机连接 `Durova-5G`。
2. 在 App 的寻物页确认服务器为 `http://192.168.1.105:8088`。
3. App 直接同步产品、绑定、基站和标签数据，无需登记、批准或 device token。
4. App 发送稳定的随机 `X-Android-Installation-Id` UUID，仅用于区分审计和幂等范围，不作为认证凭据；即使缺失仍可使用。
5. 验证绑定、解绑、亮灯和灭灯功能。

旧版本签发的 device token 可以继续使用，但不是运行前提；无效或已撤销的旧 token 会按匿名 Android 请求处理。后台不可用时，App 必须禁止绑定、解绑、亮灯和灭灯。

免登记接口和移动代理只能部署在可信现场局域网或经过身份验证的 VPN 内，严禁将 API 端口直接暴露到公网。

## 5. 旧手机绑定迁移

升级后的 App 先读取旧 `SharedPreferences`，但不再把它作为事实源。迁移流程：

1. 完成服务端首次同步，无需设备登记。
2. App 显示旧绑定迁移预览。
3. 完全相同的服务端绑定跳过。
4. 尚未绑定的灯条列为可迁移。
5. 同一灯条对应不同产品时列为冲突，绝不自动覆盖。
6. 用户确认后，仅提交可迁移记录；并发产生的 `409` 继续作为冲突保留。

迁移完成前不要清除 App 数据。需要重装时，先确认中央后台已经保存目标绑定。

## 6. 基站上线

基站通过网线连接 `Durova-5G` 所在路由器，配置：

| 字段 | 值 |
| --- | --- |
| Host | `192.168.1.105` |
| Port | `1884` |
| TLS/SSL | 关闭 |
| Username/Password | 使用受保护的现场 MQTT 凭据 |
| Client ID | 建议使用基站 SN |

保存后断电重启基站。后台应在约 30 秒内收到 `/estation/90A9F7301427/heartbeat`。在线规则为最近心跳不超过 `max(60 秒, heartbeat * 3)`。

基站原始管理网段 `192.168.172.x` 只用于配置，不得改成现场 Broker 网段示例，也不要机械替换文档中的 `192.168.172.173:8083`。

## 7. Broker 控制

网页 MQTT 页面可执行启动、停止、重启和查看日志。正式安装只控制 Windows 服务 `HighTacMqttBroker`，不会操作系统自带的 `mosquitto` 服务或端口 `1883`。

停止 Broker 会使基站和 MQTT bridge 断线，但 API、网页和数据库必须继续可用。重启后应观察：

1. TCP `1884` 恢复监听；
2. MQTT bridge 重新连接；
3. 基站重新发布心跳；
4. 页面状态依次恢复，而不是提前显示“全部正常”。

## 8. 亮灯与绑定验收

绑定规则：一个产品可绑定多条灯；一条灯同一时间只能绑定一个产品。

每次变更后检查：

- App、网页和其他手机在 2 秒内看到相同绑定；
- 产品亮灯按最多 20 条灯分批下发；
- 默认红灯、蜂鸣、闪烁、5 秒；
- 10 秒无回执显示“未确认”，不能显示成功；
- 灭灯、全灭、解绑和重绑都有操作记录；
- 查价卡片操作后仍停留在查价页面。

## 9. 备份与恢复

平台每天 02:00 使用 SQLite Online Backup API 创建一致性备份，默认保留 30 天。手工备份后检查状态、大小和 SHA-256；只有 `SUCCEEDED` 的备份允许恢复。

恢复前必须：

1. 确认目标备份 SHA-256；
2. 输入契约要求的完整确认短语；
3. 自动创建恢复前备份；
4. 恢复后重启 API 并运行 ready 检查；
5. 检查基站、灯条、绑定和操作记录。

不要直接复制正在运行的 SQLite 主文件作为正式备份，也不要提交数据库、WAL、备份或日志。

## 10. 防火墙与换网

当前只开放：

- TCP `8088`，`Private` + `LocalSubnet`；
- TCP `1884`，`Private` + `LocalSubnet`。

Windows 有时会在固定 IPv4 的 Wi-Fi 刚启动时把已验证的现场网络临时重判为
`Public`/“未识别的网络”。`HighTacPlatform` 服务以 LocalSystem 启动时会读取受保护的
`install-state.json`，仅在安装时记录的网卡仍持有同一个非回环、DHCP 已关闭的 IPv4
地址时，将该网卡恢复为 `Private`。它不会把防火墙放宽到 Public，也不会修改其他网卡。
若网卡、地址或 DHCP 状态已变化，服务会失败并在 WinSW 日志中给出明确原因，管理员需
先修正现场网络配置。

重复应用规则：

```powershell
.\installer\windows\Set-HighTacFirewall.ps1 -Action Ensure -WebPort 8088 -MqttPort 1884 -Force
```

切换到其他 Wi-Fi 前：

1. 将 WLAN IPv4 恢复 DHCP，或按新路由器重新规划静态地址；
2. 确认新地址不在 DHCP 池内；
3. 仅在可信现场网络上设置 Windows Private Profile；
4. 更新基站 MQTT 地址和 Android API 地址；
5. 重做 8088/1884、心跳和命令验收。

不要在公共 Wi-Fi 暴露明文 MQTT。当前明文 TCP 方案只适用于可信局域网。

## 11. VPN 与 Codex

本机 VPN 可能设置 HTTP 代理，导致命令行访问 LAN 地址绕到 `127.0.0.1:7890`。诊断时仅对该命令绕过代理：

```powershell
curl.exe --noproxy '*' http://192.168.1.105:8088/api/v1/health/ready
```

不要为了排障关闭 Codex 运行所需的网络或 VPN，也不要禁用 VPN 创建的适配器。Android 手机不继承电脑的 HTTP 代理环境变量。

## 12. 故障顺序

按下面顺序定位，避免把所有问题都归为“基站离线”：

1. Windows 是否开机且仍为预期静态 IP。
2. API `live` 是否响应。
3. API `ready` 中数据库和迁移是否正常。
4. Broker TCP 与 MQTT bridge 是否连接。
5. 基站最近心跳时间和 SN 是否正确。
6. 灯条是否有回执、电量和固件信息。
7. Android 是否与后台位于同一可信局域网，API 端口和防火墙是否可达。
8. 具体绑定和命令是否被唯一约束或幂等规则拒绝。

排障日志中不得出现管理员密码、旧版 Android bearer token、登记 poll secret 或 MQTT 密码。

## 13. 发布与现场验收边界

发布前由项目自动化和开发电脑完成：

- Android 单元测试、Lint、构建和 USB 安装冒烟；
- 后端、API 契约、Web 单元测试、构建和 Playwright；
- Windows 安装器构建、产物扫描、服务安装、重启与数据保留验证；
- 单台零注册手机、现有基站和少量真实灯条的基本通信检查（现场网络可用时）。

最终的 `10` 台真实 Android 手机、约 `2000` 条真实灯条并发、RF 覆盖、仓库动线和长时间稳定性验收由项目负责人在真实现场执行。该现场验收不阻断开发版本提交和安装包交付，但正式投产结论必须以负责人签字的现场结果为准。
