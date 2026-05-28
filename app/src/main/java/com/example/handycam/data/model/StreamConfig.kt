package com.example.handycam.data.model

/**
 * Configuration for video streaming.
 * Contains all parameters needed to start a stream.
 */
data class StreamConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val camera: String = "back",
    val jpegQuality: Int = 85,
    val fps: Int = 30,
    val useAvc: Boolean = false,
    val avcBitrate: Int? = null
)
