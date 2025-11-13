package com.indrek.slopper.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.indrek.slopper.ui.SlopperActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OPTIONAL: Repository to manage session enabled/disabled state reactively.
 *
 * This solves the edge case where:
 * 1. Services are running and switch shows ON
 * 2. A service crashes or permission is revoked externally
 * 3. Service writes KEY_SERVICES_ENABLED = false
 * 4. Activity doesn't know about it until onResume()
 * 5. Switch stays ON until user navigates away and back
 *
 * With this repository, the switch updates immediately when services
 * disable the session state.
 */
@Singleton
class SessionStateRepository @Inject constructor(
    private val prefs: SharedPreferences
) {

    private val _isSessionEnabledByUser = MutableStateFlow(false)
    val isSessionEnabledByUser = _isSessionEnabledByUser.asStateFlow()

    init {
        // Load initial state from SharedPreferences
        _isSessionEnabledByUser.value = prefs.getBoolean(
            SlopperActivity.KEY_SERVICES_ENABLED,
            false
        )
    }

    /**
     * Sets whether the user wants the session enabled.
     * This updates both SharedPreferences (for persistence) and the StateFlow (for reactivity).
     */
    fun setSessionEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(SlopperActivity.KEY_SERVICES_ENABLED, enabled)
        }
        _isSessionEnabledByUser.value = enabled
    }

    /**
     * Gets the current session enabled state (synchronous).
     */
    fun isSessionEnabled(): Boolean {
        return _isSessionEnabledByUser.value
    }
}