package com.example.handycam

import kotlinx.serialization.Serializable

/**
 * Data models for HTTPS server API responses.
 * Add your custom data classes here for API endpoints.
 */

@Serializable
data class ApiResponse(
    val success: Boolean,
    val message: String,
    val data: Map<String, String>? = null
)

@Serializable
data class CameraStatus(
    val isStreaming: Boolean,
    val resolution: String,
    val fps: Int,
    val codec: String
)

@Serializable
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val apiLevel: Int
)

@Serializable
data class ErrorResponse(
    val error: String,
    val code: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Example request models
 */
@Serializable
data class StartStreamRequest(
    val camera: String = "back",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30
)

@Serializable
data class UpdateSettingsRequest(
    val jpegQuality: Int? = null,
    val port: Int? = null,
    val autoStart: Boolean? = null
)
    @Serializable
    data class ServerStatus(
        val status: String,
        val streaming: Boolean,
        val port: Int,
        val uptime: Long
    )
    
    @Serializable
    data class ServerInfo(
        val name: String,
        val version: String,
        val protocol: String
    )
