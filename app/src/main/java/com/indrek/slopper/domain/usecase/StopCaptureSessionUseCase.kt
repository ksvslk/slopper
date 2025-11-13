package com.indrek.slopper.domain.usecase

import android.content.Context
import android.content.Intent
import com.indrek.slopper.data.SessionStateRepository
import com.indrek.slopper.domain.Result
import com.indrek.slopper.service.FloatingToolService
import com.indrek.slopper.service.ScreenCaptureService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * REFACTORED: Domain use case that encapsulates the business logic for stopping a capture session.
 */
class StopCaptureSessionUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStateRepo: SessionStateRepository
) {
    /**
     * Stops the capture session.
     * Returns a Result indicating success or failure.
     */
    suspend operator fun invoke(): Result<Unit> {
        return try {
            // 1. Stop services
            stopServices()

            // 2. Update session state
            sessionStateRepo.setSessionEnabled(false)

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("Failed to stop services", e)
        }
    }

    private fun stopServices() {
        // Stop ScreenCaptureService
        Intent(context, ScreenCaptureService::class.java).also {
            context.stopService(it)
        }

        // Stop FloatingToolService
        Intent(context, FloatingToolService::class.java).also {
            context.stopService(it)
        }
    }
}