package com.indrek.slopper.domain

/**
 * REFACTORED: Proper error handling using sealed classes.
 * This replaces boolean returns and provides rich error information.
 */
sealed class LimitCheckResult {
    data object Success : LimitCheckResult()
    data object LimitReached : LimitCheckResult()
    data object TimeNotReady : LimitCheckResult()
    data class Error(val message: String, val exception: Throwable? = null) : LimitCheckResult()
}

/**
 * Generic result wrapper for async operations throughout the app.
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val exception: Throwable? = null) : Result<Nothing>()
    data object Loading : Result<Nothing>()
}

/**
 * Permission check result with detailed information.
 */
sealed class PermissionResult {
    data object AllGranted : PermissionResult()
    data class MissingPermissions(val missing: List<PermissionType>) : PermissionResult()
}

enum class PermissionType {
    OVERLAY,
    NOTIFICATION
}

/**
 * Service operation results.
 */
sealed class ServiceResult {
    data object Started : ServiceResult()
    data object Stopped : ServiceResult()
    data class Failed(val reason: String) : ServiceResult()
}