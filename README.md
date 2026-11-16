# LumenLink

版本 1.5.2，Android 8.0+

## 识别方式

1. **录音源**：`UNPROCESSED → VOICE_RECOGNITION → CAMCORDER → MIC` 逐级回退。很多机型不开放 UNPROCESSED，小米部分机型只有 VOICE_RECOGNITION 能拿到没经过 AGC 的信号
2. **降噪**：Hann 窗 FFT + 谱减法，压掉空调、风扇、电源嗡声，瞬态基本不动
3. **瞬态检测**：双包络，快的跟突变（τ≈2.5–6ms），慢的跟环境（τ≈180–320ms），只有瞬间冲高才算
4. **音色比对**：24 段对数频带（125Hz–12kHz），归一化掉响度后和模板算余弦相似度

关键是第 4 步把响度归一化掉了。旁边有人大声说话，音色不对，不触发；相机快门声轻，音色对得上，照样触发。

模板由用户自己采样：按几次快门，每条样本每个频带取中位数，偶尔混进一声咳嗽影响不到。

半按对焦的提示音会被一起学进去，之后每次半按都触发补光。采样时建议切手动对焦，或者一次按到底。

---

## 权限清单

| 权限 | 用途 | 备注 |
|---|---|---|
| `CAMERA` | `CameraManager.setTorchMode` / `turnOnTorchWithStrengthLevel` | 手电筒必须；运行时申请 |
| `RECORD_AUDIO` | 音频触发（核心功能） | 首次启动即申请；界面不可见时立即停止录音 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 补光保活 | API 34 必须声明类型 |
| `POST_NOTIFICATIONS` | 常驻通知 | API 33+ 运行时申请 |
| `WAKE_LOCK` | 长曝光期间 CPU 不休眠 | 有 3 小时上限 |

前台服务类型用 `specialUse`：Google 没有为「手电筒/补光」提供专门类型，官方推荐 `specialUse`
并配 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途（见 AndroidManifest）。

> 上表只列主流程用到的权限。`AndroidManifest.xml` 里还声明了 WiFi / 蓝牙 / 网络相关权限，
> 那是早期PTP方案的遗留代码，当前版本不会触发。

---

## 代码地图

```
app/src/main/java/com/lumenlink/
├── App.kt                          Application，持有全局 LinkController
├── core/
│   ├── Logx.kt                     环形日志（logcat + 界面内诊断台）
│   └── CrashLog.kt                 崩溃日志落盘
├── flash/
│   └── FlashlightController.kt     ★ Camera2 手电筒：最高亮度、状态回调、错误翻译
├── sync/
│   ├── AudioTrigger.kt             ★ AudioRecord 采集循环 + 触发编排
│   ├── TransientDetector.kt        ★ 双包络瞬态检测（快攻慢放 + 上升沿判定）
│   ├── NoiseSuppressor.kt          谱减法降噪
│   ├── SpectrumFft.kt              FFT
│   ├── SpectrumFeatures.kt         频谱特征提取
│   ├── TimbreTemplate.kt           ★ 24 段音色模板 + 余弦相似度
│   ├── SampleRange.kt              采样条数取值范围（UI 与音频层共用）
│   ├── SyncConfig.kt               模式与参数（快门→点亮窗口的推导）
│   └── SyncEngine.kt               ★ 同步状态机 + 三层过热保护
├── service/FlashLinkService.kt     ★ 前台服务：保活 + 常驻通知 + 兜底关灯
├── link/
│   ├── LinkController.kt           ★ 全局编排：闪光灯 + 音频 + 同步 + 状态
│   └── LifetimeStats.kt            累计触发次数持久化
└── ui/                             Compose 界面（主界面 / 设置 / 教程 / 主题）
```

标 ★ 的是核心。

---

## 几个关键实现点

### 最高亮度手电筒，且不建预览

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

### 三层过热保护

1. **系统温度**：`PowerManager.currentThermalStatus`（API 29+）。≥ SEVERE 直接熄灯并告警；
   ≥ MODERATE 则把连续点亮上限压到 45s，冷却 3s。
2. **时长硬上限**：不管温度如何，连续点亮 180s 强制冷却 5s。这是给「机身不上报温度」的机型兜底的。
3. **系统关灯回调**：检测到灯被系统关掉时，先尝试恢复一次；恢复失败则如实报错，不装死。

### 音色模板的训练

每条样本先归一化掉响度，再对每个频带取**中位数**——比平均值更抗异常，偶尔混进一声咳嗽不会污染模板。

判别门限不是写死的：训练时算样本之间的自相似度，取 `minSelfSimilarity - 0.05`，
夹在 0.70–0.95 之间。样本越一致门限越高，不需要用户自己调。

### 前台服务与权限

- 锁屏 / 切后台 → **继续工作**（这正是 FGS 存在的意义，也是本 App 的核心价值）。
- 从最近任务里划掉 → `onTaskRemoved` → 关灯 + 停服务。
- 服务被销毁 → `onDestroy` 兜底关灯。
- 常驻通知里永远有一个「关闭补光」按钮。

> 目标 SDK 35 的后台启动限制意味着：`startForegroundService` 必须在 App 处于前台时发起。
> 本 App 的所有 arm 操作都来自界面点击，符合要求；`startAsForeground` 里也 try/catch 了
> `ForegroundServiceStartNotAllowedException`。

---

## 构建

### 下载安装包

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

### 从源码重新构建

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

## 作者

| | |
|---|---|
| Deepseek V4.1 Flash | 主程序 |
| 风玄 | 调试及策划，QQ 1742782203 |
| E=mc² | 策划及后勤 |

---

## 第三方资源

| 资源 | 许可 |
|---|---|
| [Oxanium](https://github.com/sevmeyer/oxanium) 字体 | SIL Open Font License 1.1 |
| Jetpack Compose / AndroidX | Apache License 2.0 |

字体文件在 `app/src/main/res/font/oxanium.ttf`，许可全文见 `app/src/main/assets/OFL_Oxanium.txt`。

---

## 其他

这个 App 只用麦克风听声音、只控制自己的闪光灯，不连接也不改写任何相机设置。

在博物馆、演出、会议等场所使用补光前，请先确认现场允许。

App仅作技术演示和灵感实现，所有因使用该App导致的损失，包括但不限于财产损失，名誉损失均与制作者无关
