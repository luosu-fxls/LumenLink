# LumenLink · 把手机 LED 变成相机的补光光源

> 一个不用 root 的 Android 应用：**手机放在相机旁边，听到快门声就点亮 LED 补光**。
> 不连接相机、不接管相机、不锁快门——你在机身上怎么调参数都不受影响。

**当前版本 1.5.2**（versionCode 10502）

---

## 主线：音频触发（为什么最终只走这条路）

工程里曾完整实现过 Wi-Fi (PTP/IP) 与 USB OTG (PTP over USB) 两条通道，但实测后**放弃了**，
原因是目标机型（尼康 Z6II）上这两条路走不通，且与「相机参数由用户自己调」的核心诉求冲突：

| 方案 | 阻塞原因 |
|---|---|
| Wi-Fi（连接至智能设备 → AP 模式） | **不开放 PTP/IP**：1-10000 全端口扫描关闭、mDNS `_ptp._tcp` 零应答 |
| Wi-Fi（连接到 PC → STA 模式） | 开放 PTP/IP 但**会锁住快门**，且必须接路由器才能连 |
| USB OTG（PTP） | 能连上但同样**会锁快门**，机身进入受控状态 |

相机的 Wi-Fi 还是**独占的**：SnapBridge 连着时第三方连不上，退出 SnapBridge 相机自动关 Wi-Fi。

**结论**：要让用户「自己在机身上调快门、光圈、ISO」，就不能让 App 接管相机。
音频触发与机型、协议、固件全部无关，也不需要 root —— 这是唯一同时满足所有约束的方案。

代码里 PTP 相关模块（`PtpIpTransport` / `PtpUsbTransport` / `NikonCamera` 等）保留，
作为协议研究与未来机型适配的基础，但**不在主流程上**。

---

## 0. 先说清楚一件事：物理边界

这条决定了整个产品的形态，必须写在最前面：

| | 氙气闪光管 | 手机 LED 手电筒 |
|---|---|---|
| 输出形态 | 微秒级瞬态脉冲 | 连续光 |
| 瞬时功率 | 极高（GN 30+） | 低（约 40–150 lux @1m） |
| 与机械快门同步 | 可以，1/1000s 也跟得上 | **不可能**（没有瞬态） |

所以本 App 里「当闪光灯用」的准确含义是 **把连续光放进曝光窗口**，而不是「闪一下」：

- **常亮补光（CONTINUOUS）** —— 真正可靠、真正有用的模式。适合人像、静物、视频、长曝光。
- **脉冲同步（PULSE）** —— 只在曝光窗口内点亮，时长 = 快门速度 + 余量。省电、降温、避免两次拍摄之间漏光。
  只在快门慢于约 1/30s 时才有意义。

另外，**PTP 不是实时同步总线**：相机的「拍完了」事件是事后通知（几十~几百 ms 延迟），
它适合统计校验，不适合做实时触发。真正的实时触发只能靠**音频瞬态检测**（延迟 10–40ms）。

---

## 1. 音频触发是怎么做的

不是简单的「声音超过阈值就开灯」——那样拍手、说话、关门全会误触发。完整链路：

1. **录音源选择**：`UNPROCESSED → VOICE_RECOGNITION → CAMCORDER → MIC` 逐级回退
   （很多机型不开放 UNPROCESSED，小米部分机型只有 VOICE_RECOGNITION 能拿到干净信号）
2. **降噪（可选）**：Hann 窗 FFT + 谱减法压制稳态底噪，保留瞬态。强度 0~1 可调，实测抑制差异约 44%
3. **瞬态检测**：自适应双门限 + 上升沿判定
   - fast 包络（τ≈2.5–6ms）跟踪突变，slow 包络（τ≈180–320ms）跟踪环境底噪
   - 判据：`rms > absoluteFloor && fast/slow > riseGate && 距上次触发 > 不应期`
   - slow 包络**始终跟随**（否则一次强声后会被永久拉高而卡死）
4. **音色匹配**：24 段对数频带（125Hz–12kHz）归一化后与模板做余弦相似度
   - 按**音色**而非音量识别 → 大声说话不会被触发，轻声快门也能识别
   - 模板由用户采样训练：每条样本对每个频带取**中位数**，抗单条异常
5. **点亮 LED**：`CameraManager.setTorchMode` / API 33+ `turnOnTorchWithStrengthLevel`（最高亮度）

采样条数默认 8 条，可在设置里 6–20 条调节。

**已知限制**：半按对焦的「嘀」声会被学进模板，导致之后每次半按都触发补光。
建议拍摄时切手动对焦（MF），或一次按到底。UI 里已明确提示。

---

## 2. 权限清单

| 权限 | 用途 | 备注 |
|---|---|---|
| `CAMERA` | `CameraManager.setTorchMode` / `turnOnTorchWithStrengthLevel` | 手电筒必须；运行时申请 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | PTP/IP over TCP 15740 | |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | 检测/连接相机热点 | |
| `NEARBY_WIFI_DEVICES`（API 33+，`neverForLocation`） | `WifiNetworkSpecifier` 代连 | 不用定位 |
| `ACCESS_FINE_LOCATION`（`maxSdkVersion=32`） | 同上，老系统要求 | |
| `RECORD_AUDIO` | 音频触发（核心功能） | 首次启动即申请；界面不可见时立即停止录音 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 补光保活 | API 34 必须声明类型 |
| `POST_NOTIFICATIONS` | 常驻通知 | API 33+ 运行时申请 |
| `WAKE_LOCK` | 长曝光期间 CPU 不休眠 | 有 3 小时上限 |
| `usb_device_filter`（class 6/1/1） | OTG 插上自动唤起 | `res/xml/usb_device_filter.xml` |

前台服务类型用 `specialUse`：Google 没有为「手电筒/补光」提供专门类型，官方推荐 `specialUse`
并配 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途（见 AndroidManifest）。

---

## 3. 代码地图

```
app/src/main/java/com/lumenlink/
├── App.kt                          Application，持有全局 LinkController
├── core/Logx.kt                    环形日志（logcat + 界面内诊断台）
├── flash/
│   └── FlashlightController.kt     ★ Camera2 手电筒：最高亮度、状态回调、错误翻译
├── ptp/
│   ├── Ptp.kt                      PTP/PTP-IP 常量（容器类型、opcode、响应码、数据类型）
│   ├── Bytes.kt                    小端读写器 + PTP 字符串编解码
│   ├── PtpTransport.kt             传输层接口（屏蔽 TCP / USB 差异）
│   ├── PtpIpTransport.kt           ★ PTP/IP：InitCommand/InitEvent 握手 + 事件通道
│   ├── PtpUsbTransport.kt          ★ PTP over USB：claimInterface + bulk 传输 + 端点探测
│   ├── PtpSession.kt               会话层：OpenSession / 属性读写 / 事件分发
│   ├── DeviceInfo.kt               GetDeviceInfo 解析（能力位图 + 机型）
│   └── DevicePropDesc.kt           ★ 属性描述符解析（枚举值/范围/读写权限）
├── nikon/
│   ├── NikonProps.kt               ★ 属性语义识别 + 曝光单位自校准
│   ├── NikonCamera.kt              ★ 高层封装：发现/读/写/拍摄
│   ├── NikonCodec.kt               按 DataType 编码 DataOut 段
│   ├── NikonMath.kt                "1/250" ↔ 4ms
│   └── NikonEvents.kt              标准事件码 + 事件学习
├── net/WifiJoiner.kt               WifiNetworkSpecifier 代连相机热点
├── sync/
│   ├── SyncConfig.kt               模式与参数（快门→点亮窗口的推导）
│   ├── SyncEngine.kt               ★ 同步状态机 + 三层过热保护
│   └── AudioTrigger.kt             ★ AudioRecord 瞬态检测（高通 + 快攻慢放包络）
├── service/FlashLinkService.kt     ★ 前台服务：保活 + 常驻通知 + 兜底关灯
├── link/LinkController.kt          ★ 全局编排：闪光灯 + 相机 + 同步 + 音频
└── ui/                             Compose 界面（状态灯 / 连接 / 补光 / 参数 / 音频 / 诊断）
```

标 ★ 的是核心。

---

## 4. 几个关键实现点

### 4.1 最高亮度手电筒，且不建预览（FlashlightController）

路径 A（首选，Android 13+）：
```kotlin
val max = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)  // API 33
cameraManager.turnOnTorchWithStrengthLevel(cameraId, max)                      // 真正的最高档
```
路径 B（兜底）：`cameraManager.setTorchMode(cameraId, true)`

为什么不用「建 CaptureSession + CONTROL_AE_MODE_ON + FLASH_MODE_TORCH」？
那条路要占住整颗摄像头、要建 Surface、功耗和发热都显著更高，而且会挡住其他 App。
纯 torch 模式是系统提供的轻量通道，长时间常亮更稳。

`CameraManager.TorchCallback` 是过热保护的第一手信号：OEM 固件在 LED 过热时会**单方面关灯**，
回调 `onTorchModeChanged(enabled=false)` / `onTorchModeUnavailable()` 会告诉我们，代码据此
区分「用户关的」和「系统关的」，并提示原因。

### 4.2 三层过热保护（SyncEngine + FlashlightController）

1. **系统温度**：`PowerManager.currentThermalStatus`（API 29+）。≥ SEVERE 直接熄灯并告警；
   ≥ MODERATE 则把连续点亮上限压到 45s，冷却 3s。
2. **时长硬上限**：不管温度如何，连续点亮 180s 强制冷却 5s。这是给「机身不上报温度」的机型兜底的。
3. **系统关灯回调**：检测到灯被系统关掉时，先尝试恢复一次；恢复失败则如实报错，不装死。

### 4.3 PTP 双传输，共用一个会话层

`PtpTransport` 只暴露「一次完整事务」：命令段 → 数据段 → 响应段。
两种传输各自处理分帧：

- **PTP/IP**：`u32 length | u32 type | body`；数据段是 `StartData(total u64)` → `Data` → `EndData`；
  事件走第 2 条 TCP（`InitEventRequest`）。
- **USB**：`u32 length | u16 type | u16 code | u32 tid | params`（12 字节头，**注意 type 是 u16**）；
  数据容器自带长度前缀，无 StartData/EndData。

USB 端点探测有一处必须踩对：**事件管道常常是 Interrupt IN 而不是 bulk IN**（PIMA 15740 就是这么定的），
所以 `bindEndpoints()` 按 `Int-IN > 第二条 bulk IN` 的顺序取，并把探测到的端点清单打进日志，
真机上万一选错可以直接看日志手工指定（`overrideEventAddr`）。

### 4.4 不猜私有属性码，靠「语义识别」

尼康的私有属性码（0xD0xx/0xD1xx）在不同机身/固件上并不一致。从开源项目抄一张表然后赌它对，
是这类项目最典型的翻车方式。本工程改成：

1. `GetDeviceInfo` 拿 `devicePropertiesSupported`；
2. 逐个 `GetDevicePropDesc`，拿到**枚举值和读写权限**；
3. 按值的形态分类：`"1/250"`/`"1.3\""` → 快门，`100/200/400/800…` → ISO，`f/2.8` → 光圈。

这样任何尼康机身（甚至走 PTP 的佳能/索尼）都能自动对上，不需要维护机型表。
对于把快门编码成纯数字的机身，用 `0x500D` 标准属性 + **单位自校准**
（试 1µs/10µs/100µs，取「算出来的原始值恰好出现在机身枚举表里」的那个）来解决。

### 4.5 快门速度 → 点亮窗口

```
holdMs = clamp(快门毫秒 + tailMs, minHoldMs, maxHoldMs)
```
用户在下拉框选 `1/250`，App 同时做两件事：把 `1/250` 写回相机（若可写），
并把 4ms 喂给同步引擎（默认 +8ms 余量，下限 20ms）。这才是「调整快门速度」和「补光」真正联动的地方。

### 4.6 前台服务与「退出必须关灯」

- 锁屏 / 切后台 → **继续工作**（这正是 FGS 存在的意义，也是本 App 的核心价值）。
- 从最近任务里划掉 → `onTaskRemoved` → 关灯 + 停服务。
- 服务被销毁 → `onDestroy` 兜底关灯。
- 常驻通知里永远有一个「关闭补光」按钮。

> 目标 SDK 35 的后台启动限制意味着：`startForegroundService` 必须在 App 处于前台时发起。
> 本 App 的所有 arm 操作都来自界面点击，符合要求；`startAsForeground` 里也 try/catch 了
> `ForegroundServiceStartNotAllowedException`。

---

## 5. 构建

### 5.1 下载安装包

安装包不放在 Git 仓库里（历史版本累计 800+ MB）。请到 **GitHub Releases** 下载最新版：

```
LumenLink-1.5.2.apk     16.12 MB
versionCode 10502
```

安装：把 APK 传到手机 → 点击安装 → 允许「安装未知来源应用」。

- 签名：v2 + v3 方案，RSA 2048，固定密钥（有效期 30 年），可以覆盖升级；
- `minSdk 26`（Android 8.0）起，`targetSdk 35`；
- 首次启动会一次性申请**相机 + 麦克风**（+ 通知）权限，必须都给——
  没有麦克风权限，App 就听不到快门声，完全无法工作。

### 5.2 从源码重新构建

在**仓库根目录**执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

脚本会跑 `assembleDebug` → `zipalign` → `apksigner` → 校验，产物落在 `dist\`。
首次运行会自动生成签名密钥 `.toolchain/lumenlink-release.jks`（口令 `lumenlink`，**不要提交到仓库**）。

**版本号只需改一处**：`LumenLink/app/build.gradle.kts` 里的 `val appVersionName = "x.y.z"`。
`versionCode` 由它自动推导（`major*10000 + minor*100 + patch`），物理上不可能再对不上。
构建脚本还内置了版本重叠守卫：新包 code 若不大于 `dist/` 里的任何旧包，直接拒绝出厂。

用 Android Studio 打开 `LumenLink/` 目录也可以正常同步构建（`local.properties` 需指向你自己的 SDK）。

### 5.3 本地工具链说明

构建用的 JDK 17 / Gradle 8.9 / Android SDK 放在仓库根目录的 `.toolchain/` 下，**不随工程分发**。
若该目录缺失，按下面装：

- JDK 17：`https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip`
- Gradle 8.9：`https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`
- cmdline-tools：`https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip`
  → `sdkmanager "platforms;android-35" "build-tools;35.0.0"`

> 踩坑记录：直连 `api.adoptium.net` 被限速到 **18 KB/s**（JDK 要下 3 小时），
> 换华为镜像后 10 MB/s。Gradle 依赖同理，工程里已把阿里云 Maven 镜像放在仓库列表最前面。

### 5.4 协议栈自测（不需要相机、不需要手机）

`tools/ptpsim/` 里有一个 **PTP/IP 假相机** 和一个**分帧逻辑的逐行移植测试**：

```powershell
pwsh -File .\LumenLink\tools\ptpsim\run_tests.ps1
```

它会启动假相机（监听 15740），验证 19 项：握手、事件通道、UTF-16 字符串、
DevicePropDesc（数值型 + 字符串型）、DataOut 分帧、枚举校验拒绝非法值、
未知 opcode、拍摄后的事件推送、以及**事件到达后命令通道不死锁**。

> 这个测试不是摆设。第一版跑出来 16/17，失败项是「收不到 CaptureComplete」，
> 追下去发现是 `PtpIpTransport` 里**命令通道与事件通道共用了一把读锁**：
> 事件泵阻塞读时永久持锁，命令通道后续每次读取都会卡死。
> 真机上表现为「连上后点什么都超时」，比丢事件严重得多。已修复并固化为回归用例。
> 没有这套测试，这个 bug 只会在你的 Z6II 上以"玄学卡顿"的形式出现。

---

## 6. 真机实测 SOP — 尼康 Z6II（主目标）

Z6II 属于 Z 系（SnapBridge 架构），和 D 系的坑**不一样**，按下面走。

### 6.0 第一步：先验证补光（与相机无关）

**不要**一上来折腾协议。补光这条链路完全不依赖相机协议，先把它确认下来：

1. 点 **开启补光（常亮）** → 通知栏出现常驻通知，手电筒亮起。
2. 锁屏 30 秒再解锁 → 灯应该还亮着（验证前台服务生效）。
3. 从最近任务划掉 App → 灯应该立刻熄灭（验证兜底逻辑）。
4. 切到 **脉冲同步**，打开音频触发，对着相机按快门 → 「联动」计数 +1，日志出现 `瞬态 rms=...`。

确认完这几条，剩下就全是协议调试。

### 6.1 相机端准备（这步做错了后面全白搭）

1. 相机：`网络菜单 → 连接到电脑 → 网络设定 → 新建配置文件`
   → 选 **直连（访问点 / Access-point mode）**。
   记下相机 SSID（形如 `NIKON Z 6_2_xxxxxxxx`）和它显示的**密码**。
2. 相机：`连接到电脑 → Wi-Fi 连接 → 开`。
3. 手机：系统 Wi-Fi 连上那个 SSID，输入相机屏幕上显示的密码，忽略"无互联网"提示。
4. **不要开尼康官方的 SnapBridge / Wireless Transmitter Utility**。Z 系的网络配置文件
   同时只认一个客户端。

### 6.2 首次连接与"配对"

Z 系的 Wi-Fi 配置文件是**按客户端 GUID 配对**的。要点：

- 配置文件**未配对**时，任何 GUID 都能连上（但相机侧处于"待配对"状态）；
- 一旦某次连接完成配对，**之后只有那个 GUID 能再连**，其他 GUID 会在握手阶段
  直接被 `Init_Fail(reason=1)` 拒掉。

所以本 App 把 GUID **持久化**到 SharedPreferences（`ClientIdentity`），正常情况下
不会变。如果你清过应用数据或重装，GUID 会变 → 表现为"连过一次，以后再也连不上"。

**修复办法（两种情况都适用）**：相机上 `网络设定 → 删除该配置文件 → 重新新建`，
然后回 App 重新连接即可重新配对。

### 6.3 连接

IP 填 `192.168.1.1`，点 **Wi-Fi 连接**。日志里期望看到：

```
客户端身份: "LumenLink" guid=xxxxxxxx-...
connecting to 192.168.1.1:15740
TCP 已连接，开始 PTP/IP 握手
handshake ok: conn=1 cam="Z 6_2_xxxxxxxx"
event channel ready            ← 没有也行，会自动降级到音频触发
OpenSession ok
GetDeviceInfo vendor=0x000A(Nikon) model="Z 6II"
属性表: 得到 N 条，被拒 M 条
```

**关键判读信号**：如果日志出现
`所有属性读取都被拒 —— 这是鉴权/配对未完成的表现，而非机身不支持`
（App 会直接把连接判为 ERROR 并给出这段提示），按 6.2 重建配置文件。
这是 Z 系最常见的"看起来连上了但什么都读不到"，**不要**误判成机型不支持。

### 6.4 Z6II 上能做什么（预期）

| 功能 | Z6II 预期 | 依据 |
|---|---|---|
| 读快门/ISO/光圈 | ✅ | 标准属性 `0x500D/0x500F/0x5007` 一般可读 |
| **改快门速度** | ✅ 大概率可以 | Z6II 是中高端机身，不像 D3x00 那样把曝光属性设成只读 |
| 遥控释放 | ✅ 但路径待定 | 见 6.5，App 会探测并告诉你哪条 opcode 可用 |
| 事件推送 | ⚠️ 不确定 | Z 系事件通道行为与 D 系不同；不可用时自动降级到音频触发 |
| 补光联动 | ✅ | 与相机协议无关，常亮 + 音频触发永远可用 |

**即使上面全都不通，补光这个核心功能也不受影响** —— 这是刻意的分层设计。

### 6.5 拿到诊断后要看的三段

点「复制诊断到剪贴板」，重点看：

1. `--- PTP 属性表 ---`
   - 找 `0x500D` 那行：`RW` = 能改快门，`R-` = 机身只读（Z6II 不应出现这种）；
   - `units=10µs` 是校准结果，Z6II 若显示别的值说明真有单位差异。

2. `--- 遥控释放路径探测 ---`
   - 列出 `0x100E / 0x90C0 / 0x90CB / 0x9435 / 0x90C2` 哪些出现在机身能力位图里，
     标注"← 优先尝试"。这直接决定"点快门按钮"走哪条路。

3. `--- Ex 属性通道探测（原始 dump）---`
   - 每个属性会同时打印 `std=...` 和 `ex=...` 的响应码与原始字节。
   - **这段是目前唯一没有公开定论的部分**（Ex 载荷头部布局各项目说法不一）。
     拿到你的真实字节后，我就能把 `probeExProps` 换成真正的解析，从而走通
     SnapBridge 原生的属性通道。

### 6.6 USB 备用路径

Z6II 的 USB-C 也支持 PTP，插上直接走 USB 通道（相机 `USB` 设为 `MTP/PTP`）。
USB 路径**没有配对问题**，属性访问通常比 Wi-Fi 更干脆，适合先验证"参数能不能改"。
缺点是手机要给相机供电，耗电快。

#### USB 连不上：先用「裸通路健康检查」

界面诊断区有一个 **「USB 裸通路健康检查（不走 PTP）」** 按钮。它完全绕开 PTP 协议，
只测 USB 能不能收发字节，用来把两类问题分开：

| 检查结果 | 结论 |
|---|---|
| `GET_DESCRIPTOR` 返回 18 字节，`bulk OUT` 写入返回 12 | **USB 链路健康**，问题在 PTP 层 |
| `GET_DESCRIPTOR` 返回 -1 | 控制管道都不通 → 线材/转接头/相机 USB 模式 |
| `bulk OUT` 全部返回 -1，但控制传输正常 | 端点在固件侧不工作 → 相机不在 PTP 模式，或固件拒绝外部控制 |
| `claimInterface(force=false)` 为 false | 被系统 MTP 服务抢先占用 |

为什么要专门做这个：Android 的 `bulkTransfer` 把 `EPIPE`（端点 halt，立即返回）、
`EAGAIN`（无数据，尽快返回）、`ETIMEDOUT`（超时）**全部折叠成 -1**。
当同一条命令的失败耗时在 11ms / 34ms / 10s 之间跳动时，说明底层原因在变，
继续在 PTP 逻辑里找是找不到东西的 —— 必须先看裸通路。

> Android 的 `UsbDeviceConnection` **没有** `clearHalt()` 方法（那是 libusb 的），
> 必须用 `controlTransfer(0x02, 0x01, 0x0000, ep.address, ...)` 手工实现。

#### USB 端点分配（Z6II 实测描述符）

PTP-over-USB 的端点不是"第几个 IN / 第几个 OUT"能猜出来的。机身上的典型布局是：

| 端点 | 用途 |
|---|---|
| `0x01` OUT | **命令容器 + 数据容器共用** |
| `0x02` OUT | 事件确认（往里写会立即被 halt，表现为 `-1`） |
| `0x81` IN | 数据/响应 |
| `0x82` IN (Interrupt) | 事件 |

工程里早期的实现按「第二个 bulk OUT 当数据端点」来选，在 Z6II 上就选中了 `0x02`，
于是出现 `发送命令容器失败（-1/16）`。现在改为：

1. **数据 OUT 与命令 OUT 共用第一个 bulk OUT**（PTP 最常见实现，风险最低）；
2. 发送失败时先发标准控制传输 `CLEAR_FEATURE(ENDPOINT_HALT)` 解除 halt 再重发；
   仍失败则自动**切换到另一个 bulk OUT 端点**并记日志；
3. 日志会打印完整端点清单与最终绑定的地址，可直接核对。

若自动探测仍不对，可在诊断里「USB 端点」一段手动确认地址，然后调用
`LinkController.setUsbEndpointOverrides(dataIn, event)` 强制指定。

> 另外：Android 的 `UsbDeviceConnection` **没有** `clearHalt()` 方法（那是 libusb 的），
> 必须用 `controlTransfer(0x02, 0x01, 0x0000, ep.address, ...)` 手工实现。

---

## 7. 已知限制（诚实清单）

1. **LED 无法替代闪光灯**：见第 0 节。远距离、高速快门、运动凝固都做不到。
2. **PTP 事件不是实时同步**：标准 `CaptureComplete`/`ObjectAdded` 是事后事件。实时靠音频。
3. **入门尼康机身**：曝光属性只读、不支持遥控释放 → 只能当「独立补光灯 + 音频联动」用。
4. **USB 与充电不可兼得**（普通 OTG 线）：手机同时在给相机供电。
5. **部分 OEM 手电筒亮度**：`FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` 只有 Android 13+ 且硬件支持才有，
   更老的系统只能拿系统缺省亮度。
6. **Wi-Fi 单客户端**：多数尼康机身同时只允许一个 PTP/IP 客户端，先关掉官方 App。
7. 音频触发在嘈杂环境（婚礼、街头）会误触发，需要调灵敏度或改用有线。

---

## 8. 路线图

- [ ] 尼康 `ChangeApplicationMode` / 私有释放快门 op：把遥控释放也做到「兼容入门机身之外的全部机型」
- [ ] 事件学习 UI：按一次快门后，让用户点「就是这个事件」把它加进 `extraShutterCodes`
- [ ] 灯光功率映射：用 `turnOnTorchWithStrengthLevel` 做「测光联动」，按 ISO/光圈给出建议档位
- [ ] 多机位：一台手机带多个相机（需要 BLE 或多次 Wi-Fi 连接）
- [ ] USB `UsbRequest` 异步事件管道（进一步压低事件延迟）
- [ ] 佳能 CCAPI / 索尼 Camera Remote SDK 的适配层（`PtpTransport` 之上再包一层 HTTP 即可）
- [ ] 尼康 Ex 属性通道（`0x943A/0x943B/0x943C`）：值形态是 **UTF-8 文本**（`"1/250"`/`"f/2.8"`/`"800"`），
      比标准二进制属性更好用；Ex 载荷的头部布局尚无公开定论，需要真机 dump 确认后再接

---

## 9. 附录：已核实的协议事实

以下数值来自 libgphoto2（`camlibs/ptp2/ptp.h`）、felis/PTP_2.0（`nikon.h`）、
dognotdog/ptpwebcam、darkgrade 的 Nikon 定义等**多个互相印证的独立开源项目**，
凡是没能印证的一律没写进来（而不是猜一个"差不多"的值）。

### 9.1 PTP/IP 包类型（Annex F）

| 类型 | 值 | | 类型 | 值 |
|---|---|---|---|---|
| InitCommandRequest | 1 | | OperationResponse | 7 |
| InitCommandAck | 2 | | Event | 8 |
| InitEventRequest | 3 | | StartData | 9 |
| InitEventAck | 4 | | Data | 10 |
| InitFail | 5 | | **CancelTransaction** | **11** |
| OperationRequest | 6 | | **EndData** | **12** |

> ⚠️ 这里有个著名坑：`EndData` 是 **12**，`CancelTransaction` 是 **11**，
> 顺序和直觉相反（很多人会写成 11/12）。写反的症状是数据段接收永远等不到结束标志。

握手：`InitCommandRequest = GUID(16B) + friendlyName(UTF-16LE, 含 NUL) + 版本(u32, 0x00010000)`；
`InitCommandAck = 连接号(u32) + 相机 GUID(16B) + 相机名 + 版本`；随后**另开一条 TCP** 发
`InitEventRequest(连接号)`。相机**同时只允许一个客户端** —— 尼康官方 App 必须彻底退出，
否则 `InitCommandRequest` 会一直挂着不返回。

### 9.2 尼康私有操作码（见 `NikonOps.kt`）

| 操作 | 值 | 说明 |
|---|---|---|
| Capture | 0x90C0 | 遥控释放快门（**比标准 0x100E 可靠得多**） |
| AfDrive | 0x90C1 | 对焦驱动 |
| ChangeCameraMode | 0x90C2 | 0=PC_CAMERA, 1=REMOTE |
| CheckEvent | 0x90C7 | 事件轮询（事件通道不可用时兜底） |
| DeviceReady | 0x90C8 | 就绪查询（**不是标准码**） |
| GetVendorPropCodes | 0x90CA | 索取私有属性码列表 ← 本工程用它来发现属性 |
| StartLiveView / EndLiveView | 0x9201 / 0x9202 | 取景 |
| ChangeApplicationMode | 0x9435 | 0=OFF, 1=ON |
| GetDevicePropDescEx / GetDevicePropValueEx / SetDevicePropValueEx | 0x943A / 0x943B / 0x943C | Ex 属性三件套 |
| （私有属性）ShutterSpeed | 0xD100 | 值形态为文本 `"1/250"` |

尼康**没有**「半按快门」的独立操作码：释放就是 0x90C0（部分资料标为 0x9207，两者在不同代机身上混用）。

### 9.3 标准曝光属性的编码（D7100 实测 dump）

| 属性 | 码 | 类型 | 编码 |
|---|---|---|---|
| FNumber | 0x5007 | UINT16 | 光圈 × 100（800 = f/8） |
| ExposureTime | 0x500D | UINT32 | 整数；D7100 dump 里 `50` 显示为 `0.0005s` → **10µs 单位** |
| ExposureIndex | 0x500F | UINT16 | 就是 ISO（1600 = ISO 1600） |
| ExposureBiasCompensation | 0x5010 | INT16 | 1/1000 EV（−5000…+5000） |
| FlashMode / ExposureProgramMode | 0x500C / 0x500E | UINT16 | 枚举 |

代码里 `NikonProps.formatStandardValue()` 按这张表做可读化，
`exposureUnitMicros` 默认 10µs（与上面的 dump 吻合），并由自校准逻辑在真机上二次确认。

### 9.8 `GetVendorPropCodes(0x90CA)` 的返回体布局按自洽长度判定

返回体没有公开标准。工程里固定试三种布局，并且**要求长度严格自洽**才采纳，
不自洽就退到裸数组而不是"猜一个计数"：

| 布局 | 结构 | 判定 |
|---|---|---|
| 前缀计数 | `u32 count` + `count × u16` | `4 + count*2` 落在 `[len-3, len]` 内 |
| 后缀计数（MTP 标准数组） | `count × u16` + `u32 count` | `count*2 + 4` 落在 `[len-3, len]` 内 |
| 裸数组 | `n × u16` | 兜底 |

日志里会打印实际命中的是哪种布局，方便核对。

### 9.4 事件

标准：`0x4002 ObjectAdded`、`0x4006 DevicePropChanged`、`0x400D CaptureComplete`、
`0x400F CaptureOverflow`。
尼康私有：`0xC101 ObjectAddedInSDRAM`、`0xC102 CaptureOverflow`、`0xC103 AdvancedTransfer`。

**没有任何一条是实时的**。它们是"拍完了"的事后通知，适合统计校验；
实时触发请用音频通道。

工程里把 `CaptureOverflow`（`0x400F` / `0xC102`）**也算作快门触发**：
它的语义是"曝光完成了，但数据没能进存储"（缓冲满、无卡）。曝光是确实发生了的，
漏掉它会让连拍/缓冲未落盘时整段漏触发 —— 比多触发一次糟得多。

`CheckEvent(0x90C7)` 的返回体布局没有公开标准，
本工程只做原始 dump（`probeCheckEvent()`），不假装解析。

### 9.5 Wi-Fi 参数

- SSID：WMU 时代 `Nikon_XXXXXXXX`；Z 系列 `NIKON_…`（机身屏幕上会显示 8 位密码）。
- 相机 IP：`192.168.1.1`，端口 15740（部分机型 `192.168.0.1`）。
- Z 系列还支持 STA 模式（加入你的路由器），此时 IP 由路由器分配，需要在机身菜单里查。
- 客户端 GUID / 友好名没有厂商强制前缀，任意 16 字节 GUID + UTF-16 名字即可。
- 错误码：`0xA004 InvalidStatus`（Z8/Z9 在"上传优先"模式）、`0xA00B NotLiveView`。

### 9.6 Z 系配对模型（**影响 Z6II 的连不上问题**）

来源：libgphoto2 issue #1314（在 Nikon ZR 固件 V1.11 上抓包 + 端到端验证）。

- 每个相机网络配置文件**只记住一个**已配对的宿主，识别依据**仅仅是**
  `Init_Command_Request` 里的 **GUID**（友好名不参与鉴权）。
- 配置文件**已配对**时，其他 GUID 会在握手阶段被 `Init_Fail, reason = 0x00000001` 拒绝。
- 配置文件**未配对**时，任何 GUID 都能连上，属性也能读（写入行为当时未测）。
- 换第二台设备 → 在相机上另建一个配置文件；删除配置文件即解除配对。
- 相机通过 mDNS `_ptp._tcp` 广播，TXT 记录 `apps` 字段反映当前配置文件状态
  （`PAIR` = 尚未配对）。

**对本工程的直接要求**：客户端 GUID 必须**跨启动稳定**。
这就是 `ClientIdentity` 存在的原因 —— 随机 GUID 会导致"第一次能连，重启后再也连不上"。
App 里已把它持久化到 SharedPreferences，并在诊断里打印出来，方便和相机端核对。

> 说明：上述行为在 ZR 上验证。Z6II 是否完全一致**未确认**，但两者同为 Z 系 SnapBridge
> 架构，且 libgphoto2 issue #1135 报告的 Z8 症状（属性查询返回 `0x2005`）与"未完成鉴权"
> 高度吻合。因此本工程按"GUID 稳定 + 配对可重建"来设计，这在两种情况下都不会错。

---

## 10. 制作者

| 头像 | 名字 | 角色 |
|---|---|---|
| ![](app/src/main/res/drawable/avatar_deepseek.png) | **Deepseek V4.1 Flash** | 主程序 |
| ![](app/src/main/res/drawable/avatar_fengxuan.png) | **风玄**（QQ：1742782203） | 调试及策划 |
| ![](app/src/main/res/drawable/avatar_emc2.png) | **E=mc²** | 策划及后勤 |

---

## 11. 第三方资源与许可

| 资源 | 许可 | 说明 |
|---|---|---|
| **Oxanium** 字体 | SIL Open Font License 1.1 | Copyright 2019 Severin Meyer。用于数字与英文字母显示，允许随软件自由嵌入再分发。 |
| **Jetpack Compose** / **AndroidX** | Apache License 2.0 | Google / Android Open Source Project |

字体文件位于 `app/src/main/res/font/oxanium.ttf`，完整许可文本见
`app/src/main/assets/OFL_Oxanium.txt`（OFL 要求随字体一同分发许可声明）。

---

## 12. 免责声明

本应用仅通过**麦克风**感知环境声音并控制**本机闪光灯**，不连接、不控制、不改写任何相机。

在博物馆、演出、会议等场所拍摄时，请先确认现场是否允许使用补光设备。