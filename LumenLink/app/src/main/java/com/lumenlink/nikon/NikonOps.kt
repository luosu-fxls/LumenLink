package com.lumenlink.nikon

/**
 * 尼康私有 PTP 操作码。
 *
 * 数值来源：libgphoto2 `camlibs/ptp2/ptp.h`、felis/PTP_2.0 的 `nikon.h`、
 * dognotdog/ptpwebcam 的 `PtpWebcamPtp.h`、darkgrade 的 Nikon operation definitions
 * （多个独立项目互相印证，可信度较高）。
 *
 * ⚠️ 这些码**不是** PTP 标准的一部分，不同代机身支持程度不同。本工程所有调用点
 * 都必须容错：返回 OperationNotSupported(0x2005) 就降级，不要去猜。
 */
object NikonOps {

    // ---------------- 9xxx：老一代（D-series / WMU 时代） ----------------
    /** 遥控释放快门（PC 模式下的"按快门"） */
    const val Capture = 0x90C0

    /** 自动对焦驱动（半按） */
    const val AfDrive = 0x90C1

    /** 切换相机模式：0 = PC_CAMERA(拍摄)，1 = REMOTE(遥控) */
    const val ChangeCameraMode = 0x90C2

    /** 轮询事件（事件通道不可用时的兜底） */
    const val CheckEvent = 0x90C7

    /** 设备就绪查询（注意：这不是标准 opcode，标准的 0x100B 是 DeleteObject） */
    const val DeviceReady = 0x90C8

    /** 取私有属性码列表（值我们最需要的：能拿到 0xD100 这类属性） */
    const val GetVendorPropCodes = 0x90CA

    /** 拍摄到 SDRAM / AfAndCapture */
    const val CaptureInSDRAM = 0x90CB

    // ---------------- 92xx：LiveView 相关 ----------------
    const val StartLiveView = 0x9201
    const val EndLiveView = 0x9202
    const val GetLiveViewImage = 0x9203
    const val MfDrive = 0x9204
    const val AfDriveCancel = 0x9206

    /**
     * 拍摄到存储媒体（部分 Z 系固件用它做遥控释放的替代路径）。
     * 0x9207 在 libgphoto2 中有两个名字，这里取更常见的语义。
     */
    const val InitiateCaptureRecInMedia = 0x9207
    const val TerminateCapture = 0x920C

    // ---------------- 94xx：新一代（Z 系列） ----------------
    /** 进入/退出应用模式：param 0 = OFF, 1 = ON */
    const val ChangeApplicationMode = 0x9435

    /** 私有属性扩展三件套（注意 GetDevicePropDescEx 是 "Custom Codec"，布局与标准不同） */
    const val GetDevicePropDescEx = 0x943A
    const val GetDevicePropValueEx = 0x943B
    const val SetDevicePropValueEx = 0x943C

    // ---------------- 私有属性码 ----------------
    /** 确认存在的尼康快门速度属性（值形态为文本，如 "1/250"） */
    const val DPC_ShutterSpeed = 0xD100

    fun opName(code: Int): String = when (code) {
        Capture -> "Nikon.Capture"
        AfDrive -> "Nikon.AfDrive"
        ChangeCameraMode -> "Nikon.ChangeCameraMode"
        CheckEvent -> "Nikon.CheckEvent"
        DeviceReady -> "Nikon.DeviceReady"
        GetVendorPropCodes -> "Nikon.GetVendorPropCodes"
        CaptureInSDRAM -> "Nikon.CaptureInSDRAM"
        StartLiveView -> "Nikon.StartLiveView"
        EndLiveView -> "Nikon.EndLiveView"
        GetLiveViewImage -> "Nikon.GetLiveViewImage"
        TerminateCapture -> "Nikon.TerminateCapture"
        ChangeApplicationMode -> "Nikon.ChangeApplicationMode"
        GetDevicePropDescEx -> "Nikon.GetDevicePropDescEx"
        GetDevicePropValueEx -> "Nikon.GetDevicePropValueEx"
        SetDevicePropValueEx -> "Nikon.SetDevicePropValueEx"
        else -> "Nikon.0x%04X".format(code)
    }
}
