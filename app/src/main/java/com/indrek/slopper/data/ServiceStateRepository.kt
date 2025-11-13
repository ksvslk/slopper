package com.indrek.slopper.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the global state of the running services.
 * This replaces the old `companion object LiveData`.
 */
@Singleton
class ServiceStateRepository @Inject constructor() {

    private val _isCaptureServiceRunning = MutableStateFlow(false)
    val isCaptureServiceRunning = _isCaptureServiceRunning.asStateFlow()

    private val _isFloatingToolRunning = MutableStateFlow(false)
    val isFloatingToolRunning = _isFloatingToolRunning.asStateFlow()

    fun setCaptureServiceState(isRunning: Boolean) {
        _isCaptureServiceRunning.value = isRunning
    }

    fun setFloatingToolState(isRunning: Boolean) {
        _isFloatingToolRunning.value = isRunning
    }
}