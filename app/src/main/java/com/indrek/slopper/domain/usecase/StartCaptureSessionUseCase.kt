package com.indrek.slopper.domain.usecase

import android.content.Context
import android.content.Intent
import com.indrek.slopper.data.PermissionStateRepository
import com.indrek.slopper.data.ServiceStateRepository
import com.indrek.slopper.data.SessionStateRepository
import com.indrek.slopper.data.UserLimitRepository
import com.indrek.slopper.domain.LimitCheckResult
import com.indrek.slopper.domain.PermissionResult
import com.indrek.slopper.domain.PermissionType
import com.indrek.slopper.domain.Result
import com.indrek.slopper.service.FloatingToolService
import com.indrek.slopper.service.ScreenCaptureService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * REFACTORED: Domain use case that encapsulates the business logic for starting a capture session.
 * This removes complex logic from the Activity/ViewModel and makes it testable.
 */
class StartCaptureSessionUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionRepo: PermissionStateRepository,
    private val serviceStateRepo: ServiceStateRepository,
    private val sessionStateRepo: SessionStateRepository,
    private val userLimitRepo: UserLimitRepository
) {
    /**
     * Attempts to start the capture session.
     * Returns a Result indicating success or the specific failure reason.
     */
    suspend operator fun invoke(isPremium: Boolean): Result<Unit> {
        // 1. Check permissions first
        val permissionResult = checkPermissions()
        if (permissionResult !is PermissionResult.AllGranted) {
            val missing = (permissionResult as PermissionResult.MissingPermissions).missing
            return Result.Error("Missing permissions: ${missing.joinToString()}")
        }

        // 2. Check user limits (only for free users)
        if (!isPremium) {
            when (val limitResult = userLimitRepo.checkAndIncrementLimit()) {
                is LimitCheckResult.Success -> {
                    // Continue
                }
                is LimitCheckResult.LimitReached -> {
                    return Result.Error("Daily generation limit reached")
                }
                is LimitCheckResult.TimeNotReady -> {
                    return Result.Error("Time verification not ready. Please try again.")
                }
                is LimitCheckResult.Error -> {
                    return Result.Error(limitResult.message, limitResult.exception)
                }
            }
        }

        // 3. Start services
        try {
            startServices()

            // 4. Update session state
            sessionStateRepo.setSessionEnabled(true)

            return Result.Success(Unit)
        } catch (e: Exception) {
            return Result.Error("Failed to start services", e)
        }
    }

    private fun checkPermissions(): PermissionResult {
        val missing = mutableListOf<PermissionType>()

        if (!permissionRepo.hasAllPermissions()) {
            // We could make this more granular if needed
            if (!permissionRepo.hasOverlayPermission.value) {
                missing.add(PermissionType.OVERLAY)
            }
            if (!permissionRepo.hasNotificationPermission.value) {
                missing.add(PermissionType.NOTIFICATION)
            }
        }

        return if (missing.isEmpty()) {
            PermissionResult.AllGranted
        } else {
            PermissionResult.MissingPermissions(missing)
        }
    }

    private fun startServices() {
        // Start ScreenCaptureService
        Intent(context, ScreenCaptureService::class.java).also {
            context.startForegroundService(it)
        }

        // Start FloatingToolService
        Intent(context, FloatingToolService::class.java).also {
            context.startService(it)
        }
    }
}