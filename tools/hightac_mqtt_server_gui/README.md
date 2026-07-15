# HighTac MQTT Server Setup

这是一个给 Windows 电脑使用的 HighTac 声光寻物 MQTT 服务器一键配置工具。

## 文件

- `dist/HighTacMqttServerSetup.exe`：可直接复制到其他 Windows 电脑运行的 GUI 程序。
- `hightac_mqtt_server_gui.py`：Python 源码，内置轻量 MQTT 3.1.1 Broker。
- `HighTacMqttServerSetup.spec`：PyInstaller 打包配置。

## 使用步骤

1. 将 `dist/HighTacMqttServerSetup.exe` 复制到要作为 MQTT 服务器的 Windows 电脑。
2. 建议右键“以管理员身份运行”，这样软件可以自动添加 Windows 防火墙入站规则。
3. 确认界面中的本机 `Broker地址` 是和基站、手机处于同一局域网的 IP。
4. 点击“一键启动服务器”。
5. 将界面输出的以下信息填写到 HighTac App：
   - 基站SN
   - Broker地址
   - 端口
   - 用户名
   - 密码
   - TLS/SSL 关闭
6. 将同一套 MQTT 信息填写到基站内置管理页：
   - MQTT服务器地址和端口：`Broker地址:端口`
   - 用户名
   - 密码
   - TLS/SSL 关闭

## 默认配置

- 基站SN：`90A9F7301427`
- 当前现场 Wi-Fi：`Durova-5G`
- 当前现场 Broker 地址：`192.168.1.105`
- 端口：`1884`
- 用户名：`hightac_mqtt`
- 密码：`hightac-light`
- TLS/SSL：关闭

## 注意

- 软件窗口关闭后，内置 MQTT 服务器也会停止。
- 如果基站或手机连不上，优先检查 Windows 防火墙、电脑 IP 是否选对、基站和手机是否在同一个路由器/局域网下。
- 如果 `1884` 端口被占用，可以改成其他端口，但 App 和基站必须填同一个端口。
- `192.168.1.105` 是当前 `Durova-5G` 的静态地址。切换其他 Wi-Fi 时应恢复 DHCP 或按新网段重配静态地址，并在软件、App 和基站中选择同一个新地址。
- 软件检测到多个网卡时，会优先选择当前现场的 `192.168.1.105`，其次选择 `192.168.x.x`；启动前仍应核对界面地址对应实际 Wi-Fi 网卡。
- 当前 `Durova-5G` 在 Windows 中被识别为 `Public`，已有防火墙规则适用于所有 Profile。确认现场网络可信后，建议改为 `Private` 并将 1884 入站规则收窄到 `Private`；切换到不受信任 Wi-Fi 前应停止服务器并禁用规则。
