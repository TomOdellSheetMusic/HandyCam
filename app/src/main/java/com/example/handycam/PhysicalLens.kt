package com.example.handycam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri

/** A physical camera ID is only meaningful together with its logical parent. */
object PhysicalLens {
    fun key(logical: String, physical: String): String =
        "physical:${Uri.encode(logical)}:${Uri.encode(physical)}"

    fun parse(key: String): Pair<String, String>? {
        if (!key.startsWith("physical:")) return null
        val parts = key.split(':')
        if (parts.size != 3 || parts[1].isEmpty() || parts[2].isEmpty()) return null
        return Uri.decode(parts[1]) to Uri.decode(parts[2])
    }

    fun valid(manager: CameraManager, key: String): Boolean = try {
        val pair = parse(key) ?: return false
        pair.first in manager.cameraIdList &&
            pair.second in manager.getCameraCharacteristics(pair.first).physicalCameraIds
    } catch (_: Exception) { false }
}
