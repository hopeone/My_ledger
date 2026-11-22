package com.hopeone.myledger

import android.content.Context
import android.os.Build

// Drive allows 124 UTF-8 bytes for the key and value combined.
internal const val DEVICE_NAME_KEY = "deviceName"
internal const val DEVICE_MODEL_KEY = "deviceModel"
internal val MAX_DEVICE_NAME_BYTES = 124 - DEVICE_NAME_KEY.length

internal data class BackupDevice(val name: String, val model: String)

internal class LedgerDeviceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("ledger_device", Context.MODE_PRIVATE)
    private val model = Build.MODEL.trim().ifEmpty { "Android 기기" }.fitDriveModel()

    fun device() = BackupDevice(preferences.getString("name", null) ?: model, model)

    fun saveName(name: String) {
        preferences.edit().putString("name", name).apply()
    }

    private fun String.fitDriveModel(): String {
        var end = length
        while (substring(0, end).toByteArray().size > 124 - DEVICE_MODEL_KEY.length) {
            end = offsetByCodePoints(end, -1)
        }
        return substring(0, end)
    }
}
