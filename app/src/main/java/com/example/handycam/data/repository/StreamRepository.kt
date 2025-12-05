package com.example.handycam.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents the current state of the streaming service.
 */
data class StreamState(
    val isActive: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val camera: String = "",
    val error: String? = null
)

/**
 * Repository for stream management.
 * Tracks streaming state and provides interface to control the service.
 */
@Singleton
class StreamRepository @Inject constructor() {
    
    private val _streamState = MutableStateFlow(StreamState())
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    fun updateStreamState(
        isActive: Boolean,
        host: String = "",
        port: Int = 0,
        camera: String = "",
        error: String? = null
    ) {
        _streamState.value = StreamState(
            isActive = isActive,
            host = host,
            port = port,
            camera = camera,
            error = error
        )
    }

    fun clearError() {
        _streamState.value = _streamState.value.copy(error = null)
    }
}
