package com.example.handycam.data.model

/**
 * Represents camera device information.
 */
data class CameraInfo(
    val id: String,
    val displayName: String,
    val facing: String,
    val focalLength: String? = null
)
