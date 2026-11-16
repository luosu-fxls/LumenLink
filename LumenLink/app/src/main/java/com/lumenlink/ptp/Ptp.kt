package com.lumenlink.ptp

/**
 * PTP / PTP-IP 常量。数值取自 PIMA 15740:2000 与 15740:2008 Annex F(PTP/IP)。
 * 尼康私有扩展见 [com.lumenlink.nikon.NikonProps]，那一部分必须用真机 Dump 校验。
 */
object Ptp {

    // ---------------- PTP/IP 包类型（Annex F） ----------------
    const val PKT_INIT_COMMAND_REQUEST = 1
    const val PKT_INIT_COMMAND_ACK = 2
    const val PKT_INIT_EVENT_REQUEST = 3
    const val PKT_INIT_EVENT_ACK = 4
    const val PKT_INIT_FAIL = 5
    const val PKT_OPERATION_REQUEST = 6
    const val PKT_OPERATION_RESPONSE = 7
    const val PKT_EVENT = 8
    const val PKT_START_DATA = 9
    const val PKT_DATA = 10           // 0x0A
    const val PKT_CANCEL_TRANSACTION = 11 // 0x0B
    const val PKT_END_DATA = 12       // 0x0C  ← 注意：EndData 在 Cancel 之后，不是 11
    const val PKT_PING = 13
    const val PKT_PONG = 14

    // ---------------- USB 容器类型（15740 §13） ----------------
    const val CONT_COMMAND = 1
    const val CONT_DATA = 2
    const val CONT_RESPONSE = 3
    const val CONT_EVENT = 4

    /** PTP/IP 的 DataPhaseInfo 取值 */
    const val DATA_PHASE_NONE = 0
    const val DATA_PHASE_OUT = 1   // 发起方 -> 相机
    const val DATA_PHASE_IN = 2    // 相机 -> 发起方

    // ---------------- 标准操作码 ----------------
    const val OC_GetDeviceInfo = 0x1001
    const val OC_OpenSession = 0x1002
    const val OC_CloseSession = 0x1003
    const val OC_GetStorageIDs = 0x1004
    const val OC_GetNumObjects = 0x1006
    const val OC_GetObjectHandles = 0x1007
    const val OC_GetObjectInfo = 0x1008
    const val OC_GetObject = 0x1009
    const val OC_DeleteObject = 0x100B
    const val OC_InitiateCapture = 0x100E
    const val OC_GetDevicePropDesc = 0x1014
    const val OC_GetDevicePropValue = 0x1015
    const val OC_SetDevicePropValue = 0x1016
    const val OC_ResetDevicePropValue = 0x1017
    const val OC_TerminateOpenCapture = 0x1018
    const val OC_ResetDevice = 0x1010
    const val OC_PowerDown = 0x1013
    const val OC_GetPartialObject = 0x101B

    // 注意：本工程刻意不使用 GetEvent / DeviceReady 这类「轮询」操作码。
    // 事件的获取走「推」的路径——PTP/IP 的事件通道（第 2 条 TCP）或 USB 的 event bulk IN 端点。
    // 这样既不必猜测轮询操作码，延迟也更低（对快门同步是决定性的）。

    // ---------------- 响应码 ----------------
    const val RC_UNDEFINED = 0x2000
    const val RC_OK = 0x2001
    const val RC_GeneralError = 0x2002
    const val RC_SessionNotOpen = 0x2003
    const val RC_InvalidTransactionID = 0x2004
    const val RC_OperationNotSupported = 0x2005
    const val RC_ParameterNotSupported = 0x2006
    const val RC_IncompleteTransfer = 0x2007
    const val RC_InvalidStorageID = 0x2008
    const val RC_InvalidObjectHandle = 0x2009
    const val RC_DevicePropNotSupported = 0x200A
    const val RC_InvalidObjectFormatCode = 0x200B
    const val RC_StoreFull = 0x200C
    const val RC_ObjectWriteProtected = 0x200D
    const val RC_StoreReadOnly = 0x200E
    const val RC_AccessDenied = 0x200F
    const val RC_NoThumbnailPresent = 0x2010
    const val RC_SelfTestFailed = 0x2011
    const val RC_PartialDeletion = 0x2012
    const val RC_StoreNotAvailable = 0x2013
    const val RC_SpecificationByFormatUnsupported = 0x2014
    const val RC_NoValidObjectInfo = 0x2015
    const val RC_InvalidCodeFormat = 0x2016
    const val RC_UnknownVendorCode = 0x2017
    const val RC_CaptureAlreadyTerminated = 0x2018
    const val RC_DeviceBusy = 0x2019
    const val RC_InvalidParentObject = 0x201A
    const val RC_InvalidDevicePropFormat = 0x201B
    const val RC_InvalidDevicePropValue = 0x201C
    const val RC_InvalidParameter = 0x201D
    const val RC_SessionAlreadyOpen = 0x201E
    const val RC_TransactionCancelled = 0x201F
    const val RC_SpecificationOfDestinationUnsupported = 0x2020

    // ---------------- 数据类型（DevicePropDesc 用） ----------------
    const val DT_INT8 = 0x0001
    const val DT_UINT8 = 0x0002
    const val DT_INT16 = 0x0003
    const val DT_UINT16 = 0x0004
    const val DT_INT32 = 0x0005
    const val DT_UINT32 = 0x0006
    const val DT_INT64 = 0x0007
    const val DT_UINT64 = 0x0008
    const val DT_INT128 = 0x0009
    const val DT_UINT128 = 0x000A
    const val DT_INT8ARRAY = 0x4001
    const val DT_UINT8ARRAY = 0x4002
    const val DT_STRING = 0xFFFF

    // ---------------- 标准设备属性码（曝光相关） ----------------
    const val DPC_BatteryLevel = 0x5001
    const val DPC_FunctionalMode = 0x5002
    const val DPC_ImageSize = 0x5003
    const val DPC_CompressionSetting = 0x5004
    const val DPC_WhiteBalance = 0x5005
    const val DPC_RGBGain = 0x5006
    const val DPC_FNumber = 0x5007
    const val DPC_FocalLength = 0x5008
    const val DPC_FocusDistance = 0x5009
    const val DPC_FocusMode = 0x500A
    const val DPC_ExposureMeteringMode = 0x500B
    const val DPC_FlashMode = 0x500C
    const val DPC_ExposureTime = 0x500D
    const val DPC_ExposureProgramMode = 0x500E
    const val DPC_ExposureIndex = 0x500F
    const val DPC_ExposureBiasCompensation = 0x5010
    const val DPC_DateTime = 0x5011
    const val DPC_CaptureDelay = 0x5012
    const val DPC_StillCaptureMode = 0x5013

    fun opcodeName(code: Int): String = when (code) {
        OC_GetDeviceInfo -> "GetDeviceInfo"
        OC_OpenSession -> "OpenSession"
        OC_CloseSession -> "CloseSession"
        OC_GetStorageIDs -> "GetStorageIDs"
        OC_InitiateCapture -> "InitiateCapture"
        OC_GetDevicePropDesc -> "GetDevicePropDesc"
        OC_GetDevicePropValue -> "GetDevicePropValue"
        OC_SetDevicePropValue -> "SetDevicePropValue"
        OC_ResetDevicePropValue -> "ResetDevicePropValue"
        OC_TerminateOpenCapture -> "TerminateOpenCapture"
        else -> "0x%04X".format(code)
    }

    fun responseName(code: Int): String = when (code) {
        RC_OK -> "OK"
        RC_GeneralError -> "GeneralError"
        RC_SessionNotOpen -> "SessionNotOpen"
        RC_InvalidTransactionID -> "InvalidTransactionID"
        RC_OperationNotSupported -> "OperationNotSupported"
        RC_ParameterNotSupported -> "ParameterNotSupported"
        RC_DevicePropNotSupported -> "DevicePropNotSupported"
        RC_StoreNotAvailable -> "StoreNotAvailable"
        RC_AccessDenied -> "AccessDenied"
        RC_DeviceBusy -> "DeviceBusy"
        RC_InvalidDevicePropFormat -> "InvalidDevicePropFormat"
        RC_InvalidDevicePropValue -> "InvalidDevicePropValue"
        RC_InvalidParameter -> "InvalidParameter"
        RC_SessionAlreadyOpen -> "SessionAlreadyOpen"
        RC_TransactionCancelled -> "TransactionCancelled"
        RC_UnknownVendorCode -> "UnknownVendorCode"
        RC_NoValidObjectInfo -> "NoValidObjectInfo"
        RC_CaptureAlreadyTerminated -> "CaptureAlreadyTerminated"
        else -> "0x%04X".format(code)
    }

    fun datatypeName(dt: Int): String = when (dt) {
        DT_INT8 -> "INT8"
        DT_UINT8 -> "UINT8"
        DT_INT16 -> "INT16"
        DT_UINT16 -> "UINT16"
        DT_INT32 -> "INT32"
        DT_UINT32 -> "UINT32"
        DT_INT64 -> "INT64"
        DT_UINT64 -> "UINT64"
        DT_STRING -> "STRING"
        else -> "DT_0x%04X".format(dt)
    }

    fun sizeOfDatatype(dt: Int): Int = when (dt) {
        DT_INT8, DT_UINT8 -> 1
        DT_INT16, DT_UINT16 -> 2
        DT_INT32, DT_UINT32 -> 4
        DT_INT64, DT_UINT64 -> 8
        DT_INT128, DT_UINT128 -> 16
        else -> -1
    }
}
