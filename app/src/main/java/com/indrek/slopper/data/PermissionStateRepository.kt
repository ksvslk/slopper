package com.indrek.slopper.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for permission state.
 * This repository actively checks permissions and provides a Flow for the UI to observe.
 */
@Singleton
class PermissionStateRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _hasOverlayPermission = MutableStateFlow(false)
    val hasOverlayPermission = _hasOverlayPermission.asStateFlow()

    private val _hasNotificationPermission = MutableStateFlow(false)
    val hasNotificationPermission = _hasNotificationPermission.asStateFlow()

    /**
     * Call this method whenever permissions might have changed
     * (e.g., after onResume, after permission grant, etc.)
     */
    fun refreshPermissions() {
        _hasOverlayPermission.value = Settings.canDrawOverlays(context)

        _hasNotificationPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No notification permission needed on older Android versions
        }
    }

    /**
     * Synchronous check if all required permissions are granted.
     * Useful for quick checks without collecting flows.
     */
    fun hasAllPermissions(): Boolean {
        val hasOverlay = Settings.canDrawOverlays(context)
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return hasOverlay && hasNotification
    }
}