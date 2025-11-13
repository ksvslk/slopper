package com.indrek.slopper.data

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.indrek.slopper.C.FREE_TIER_LIMIT
import com.indrek.slopper.ui.SlopperActivity.Companion.KEY_DAILY_LIMIT_COUNT
import com.indrek.slopper.ui.SlopperActivity.Companion.KEY_DAILY_LIMIT_DAY
import com.indrek.slopper.SlopperApplication
import com.indrek.slopper.domain.LimitCheckResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * REFACTORED: Now returns proper LimitCheckResult types instead of boolean.
 * The single source of truth for managing the user's free daily limit.
 * Now with reactive StateFlow for automatic UI updates!
 */
@Singleton
class UserLimitRepository @Inject constructor(
    private val app: SlopperApplication,
    private val prefs: SharedPreferences
) {

    // Reactive state flow for limit data
    private val _limitDataFlow = MutableStateFlow(UserLimitData(isReady = false))
    val limitDataFlow = _limitDataFlow.asStateFlow()

    /**
     * READ-ONLY check for the UI.
     * Gets the current remaining generations without changing the count.
     * This is kept for backward compatibility but also updates the flow.
     */
    fun getLimitData(): UserLimitData {
        val data = calculateLimitData()
        _limitDataFlow.value = data // Update the flow
        return data
    }

    /**
     * Internal method to calculate limit data
     */
    private fun calculateLimitData(): UserLimitData {
        val trustedTime = app.trustedTimeClient?.computeCurrentUnixEpochMillis()

        if (trustedTime == null) {
            // Time client is NOT ready. Show a loading state.
            return UserLimitData(isReady = false)
        }

        // Time client IS ready. Do the real calculation.
        val trustedCalendar = Calendar.getInstance().apply { timeInMillis = trustedTime }
        val trustedDay = trustedCalendar.get(Calendar.DAY_OF_YEAR)

        val lastGenDay = prefs.getInt(KEY_DAILY_LIMIT_DAY, -1)
        val generationCount = prefs.getInt(KEY_DAILY_LIMIT_COUNT, 0)

        val remaining: Int = if (trustedDay != lastGenDay) {
            FREE_TIER_LIMIT
        } else {
            (FREE_TIER_LIMIT - generationCount).coerceAtLeast(0)
        }
        return UserLimitData(remaining = remaining, total = FREE_TIER_LIMIT, isReady = true)
    }

    /**
     * REFACTORED: Now returns LimitCheckResult instead of boolean.
     * The main "security" check.
     * Checks if a generation is allowed, and if so, increments the counter.
     * Also updates the reactive flow so UI updates automatically!
     *
     * @return LimitCheckResult indicating success or the specific failure reason.
     */
    @Synchronized
    fun checkAndIncrementLimit(): LimitCheckResult {
        val trustedTime: Long? = app.trustedTimeClient?.computeCurrentUnixEpochMillis()

        if (trustedTime == null) {
            Log.e(TAG, "TrustedTimeClient is null or has no time signal. Denying request.")
            _limitDataFlow.value = calculateLimitData()
            return LimitCheckResult.TimeNotReady
        }

        val trustedCalendar = Calendar.getInstance().apply { timeInMillis = trustedTime }
        val trustedDay = trustedCalendar.get(Calendar.DAY_OF_YEAR)

        val lastGenDay = prefs.getInt(KEY_DAILY_LIMIT_DAY, -1)
        var generationCount = prefs.getInt(KEY_DAILY_LIMIT_COUNT, 0)

        val result = if (trustedDay != lastGenDay) {
            // New day, reset counter to 1
            prefs.edit {
                putInt(KEY_DAILY_LIMIT_COUNT, 1)
                putInt(KEY_DAILY_LIMIT_DAY, trustedDay)
            }
            Log.d(TAG, "New trusted day. Count set to 1.")
            LimitCheckResult.Success
        } else {
            // Same day, check limit
            if (generationCount < FREE_TIER_LIMIT) {
                // Limit not reached, increment
                generationCount++
                prefs.edit { putInt(KEY_DAILY_LIMIT_COUNT, generationCount) }
                Log.d(TAG, "Same trusted day. Count incremented to $generationCount.")
                LimitCheckResult.Success
            } else {
                // Limit IS reached
                Log.w(TAG, "Same trusted day. Count ($generationCount) at or over limit.")
                LimitCheckResult.LimitReached
            }
        }

        // Update the flow after modifying the count
        _limitDataFlow.value = calculateLimitData()

        return result
    }

    /**
     * DEPRECATED: Use checkAndIncrementLimit() instead.
     * Kept for backward compatibility during migration.
     */
    @Deprecated(
        message = "Use checkAndIncrementLimit() which returns detailed LimitCheckResult",
        replaceWith = ReplaceWith("checkAndIncrementLimit()")
    )
    fun canIncrementGeneration(): Boolean {
        return when (checkAndIncrementLimit()) {
            is LimitCheckResult.Success -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "UserLimitRepository"
    }
}